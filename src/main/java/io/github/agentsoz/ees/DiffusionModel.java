package io.github.agentsoz.ees;

/*-
 * #%L
 * Emergency Evacuation Simulator
 * %%
 * Copyright (C) 2014 - 2018 by its authors. See AUTHORS file.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */


import io.github.agentsoz.bdiabm.data.ActionPerceptContainer;
import io.github.agentsoz.dataInterface.DataClient;
import io.github.agentsoz.dataInterface.DataServer;
import io.github.agentsoz.dataInterface.DataSource;
import io.github.agentsoz.socialnetwork.ICModel;
import io.github.agentsoz.socialnetwork.SNConfig;
import io.github.agentsoz.socialnetwork.SocialNetworkManager;
import io.github.agentsoz.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


public class DiffusionModel implements DataSource<HashMap<String,DiffusedContent>>, DataClient<Object> {

    private final Logger logger = LoggerFactory.getLogger(DiffusionModel.class);

    private static final String eConfigFile = "configFile";

    private DataServer dataServer;
    private double startTimeInSeconds = -1;
    private SocialNetworkManager snManager;
    private double lastUpdateTimeInMinutes = -1;
    private Time.TimestepUnit timestepUnit = Time.TimestepUnit.SECONDS;
    private String configFile = null;
    private List<String> agentsIds = null;


    Map<String, Set> localContentFromAgents;
    ArrayList<String> globalContentFromAgents;

    public DiffusionModel(String configFile) {
        this.snManager = (configFile==null) ? null : new SocialNetworkManager(configFile);
        this.localContentFromAgents = new HashMap<>();
        this.globalContentFromAgents =  new ArrayList<String>();
    }

    public DiffusionModel(Map<String, String> opts, DataServer dataServer, List<String> agentsIds) {
        parse(opts);
        this.snManager = (configFile==null) ? null : new SocialNetworkManager(configFile);
        this.localContentFromAgents = new HashMap<>();
        this.globalContentFromAgents =  new ArrayList<String>();
        this.dataServer = dataServer;
        this.agentsIds = agentsIds;
    }

    private void parse(Map<String, String> opts) {
        if (opts == null) {
            return;
        }
        for (String opt : opts.keySet()) {
            logger.info("Found option: {}={}", opt, opts.get(opt));
            switch(opt) {
                case Config.eGlobalStartHhMm:
                    String[] tokens = opts.get(opt).split(":");
                    setStartHHMM(new int[]{Integer.parseInt(tokens[0]),Integer.parseInt(tokens[1])});
                    break;
                case eConfigFile:
                    configFile = opts.get(opt);
                    break;
                default:
                    logger.warn("Ignoring option: " + opt + "=" + opts.get(opt));
            }
        }
    }

    public void setStartHHMM(int[] hhmm) {
        startTimeInSeconds = Time.convertTime(hhmm[0], Time.TimestepUnit.HOURS, timestepUnit)
                + Time.convertTime(hhmm[1], Time.TimestepUnit.MINUTES, timestepUnit);
    }


    public void init(List<String> idList) {

        this.snManager.setupSNConfigsAndLogs(); // first, setup configs and create log
        for (String id : idList) {
            this.snManager.createSocialAgent(id); //populate agentmap
        }
        this.snManager.genNetworkAndDiffModels(); // setup configs, gen network and diffusion models
        this.snManager.printSNModelconfigs();
        //subscribe to BDI data updates
      //  this.dataServer.subscribe(this, Constants.SOCIAL_NETWORK_CONTENT);
        this.dataServer.subscribe(this, Constants.BDI_REASONING_UPDATES);
    }

    protected void stepDiffusionProcess(HashMap<String,DiffusedContent> allAgentContentsMap) {
        snManager.diffuseContent(); // step the diffusion model
        if (snManager.getDiffModel() instanceof ICModel) {
            ICModel icModel = (ICModel) snManager.getDiffModel();
            icModel.recordCurrentStepSpread(dataServer.getTime());

            HashMap<String, ArrayList<String>> latestUpdate = icModel.getLatestDiffusionUpdates();
            if (!latestUpdate.isEmpty()) {

                for(Map.Entry<String,ArrayList<String>> contents: latestUpdate.entrySet()) {
                    String contentType = contents.getKey();
                    ArrayList<String> agentIDs = contents.getValue();

                    for(String id: agentIDs) { // for each agent create a DiffusedContent and put content type and parameters
                        DiffusedContent content = getOrCreateDiffusedContent(id,allAgentContentsMap);
                        String[] params = {Constants.ACTIVE};
                        content.getContentsMap().put(contentType,params );
                    }
                }

                logger.debug("put timed diffusion updates for ICModel at {}", dataServer.getTime());
            }
        }


    }

    public DiffusedContent getOrCreateDiffusedContent(String agentId, HashMap<String,DiffusedContent> diffusedContentsMap) {
        DiffusedContent content = diffusedContentsMap.get(agentId);
        if (content == null) {
            content = new DiffusedContent();
            diffusedContentsMap.put(agentId, content);
        }

        return content;
    }
    @Override
    public HashMap<String,DiffusedContent> sendData(double timestep, String dataType) {
        Double nextTime = timestep + SNConfig.getDiffturn();

        //create the data structure that is passed to the BDI side
        HashMap<String, DiffusedContent> currentStepDiffusedContents = new HashMap<>();

        if (nextTime != null) {
            dataServer.registerTimedUpdate(Constants.DIFFUSION, this, nextTime);
            // update the model with any new messages form agents
            ICModel icModel = (ICModel) this.snManager.getDiffModel();

            if (!localContentFromAgents.isEmpty()) { // update local content
                Map<String, String[]> map = new HashMap<>();
                for (String key : localContentFromAgents.keySet()) {
                    Object[] set = localContentFromAgents.get(key).toArray(new String[0]);
                    String[] newSet = new String[set.length];
                    for (int i = 0; i < set.length; i++) {
                        newSet[i] = (String)set[i];
                    }
                    map.put(key,newSet);
                    logger.info(String.format("At time %.0f, total %d agents will spread new message: %s", timestep, newSet.length, key));
                    logger.info("Agents spreading new message are: {}", Arrays.toString(newSet));
                }
                icModel.updateSocialStatesFromLocalContent(map);
            }

            if(!globalContentFromAgents.isEmpty()) { // update global contents

                logger.info("Global content received to spread: {}", globalContentFromAgents.toString());
                icModel.updateSocialStatesFromGlobalContent(globalContentFromAgents);

            }

            // step the model before begin called again
            stepDiffusionProcess(currentStepDiffusedContents);

            // clear the contents
            globalContentFromAgents.clear();
            localContentFromAgents.clear();

        }

        double currentTime = Time.convertTime(timestep, timestepUnit, Time.TimestepUnit.MINUTES);
        lastUpdateTimeInMinutes = currentTime;
        return (currentStepDiffusedContents.isEmpty()) ? null : currentStepDiffusedContents;

    }


    @Override
    public void receiveData(double time, String dataType, Object data) { // data package from the BDI side

        switch (dataType) {
            case Constants.BDI_REASONING_UPDATES: // update Diffusion model based on BDI updates

                //create the data structure that is passed to the BDI side
                SNUpdates newUpdates = (SNUpdates) data;

                if (!(data instanceof SNUpdates)) {
                    logger.error("received unknown data: " + data.toString());
                    break;
                }


                    //process local contents
                    for(String localContent: newUpdates.getContentsMap().keySet()){
                        String[] contents = (String[]) newUpdates.getContentsMap().get(localContent);
                        String msg = contents[0];
                        String agentId = contents[1];
                        // do something with parameters
                        logger.debug("Agent {} received local content type {}. Message: {}",agentId,localContent);
                        Set<String> agents = (localContentFromAgents.containsKey(localContent)) ? localContentFromAgents.get(localContent) :
                                new HashSet<>();
                        agents.add(agentId);
                        localContentFromAgents.put(localContent, agents);
                    }

                    //process global (broadcast) contents
                    for(String globalContent: newUpdates.getBroadcastContentsMap().keySet()){
                        logger.debug("received global content " + globalContent);
                        if(!globalContentFromAgents.contains(globalContent)) {
                            globalContentFromAgents.add(globalContent);
                        }
                        String[] params = (String[])newUpdates.getContentsMap().get(globalContent);
                        // do something with parameters

                    }

                    //process SN actions
                    for(String action: newUpdates.getSNActionsMap().keySet()){
                        Object[] params = newUpdates.getContentsMap().get(action);
                        // do something with parameters
                    }
                break;
            default:
                throw new RuntimeException("Unknown data type received: " + dataType);
        }
    }



    /**
     * Set the time step unit for this model
     * @param unit the time step unit to use
     */
    void setTimestepUnit(Time.TimestepUnit unit) {
        timestepUnit = unit;
    }


    public void start() {
        if (snManager != null) {
            init(agentsIds);
            setTimestepUnit(Time.TimestepUnit.MINUTES);
            dataServer.registerTimedUpdate(Constants.DIFFUSION, this, Time.convertTime(startTimeInSeconds, Time.TimestepUnit.SECONDS, timestepUnit));
        } else {
            logger.warn("started but will be idle forever!!");
        }
    }

    /**
     * Start publishing data
     * @param hhmm an array of size 2 with hour and minutes representing start time
     */
    public void start(int[] hhmm) {
        double startTimeInSeconds = Time.convertTime(hhmm[0], Time.TimestepUnit.HOURS, Time.TimestepUnit.SECONDS)
                + Time.convertTime(hhmm[1], Time.TimestepUnit.MINUTES, Time.TimestepUnit.SECONDS);
        dataServer.registerTimedUpdate(Constants.DIFFUSION, this, startTimeInSeconds);
    }


    public void finish() {
        // cleaning

        if(snManager == null) { // return if the diffusion model is not executed
            return;
        }
        if (snManager.getDiffModel() instanceof ICModel) {

            //terminate diffusion model and output diffusion data
            ICModel icModel = (ICModel) this.snManager.getDiffModel();
            icModel.finish();
            icModel.getDataCollector().writeSpreadDataToFile();
        }
    }

    /**
     * Sets the publish/subscribe data server
     * @param dataServer the server to use
     */
    void setDataServer(DataServer dataServer) {
        this.dataServer = dataServer;
    }

    public DataServer getDataServer() {
        return dataServer;
    }


    public SocialNetworkManager getSnManager() {
        return snManager;
    }

    public Map<String, Set> getLocalContentFromAgents() {
        return localContentFromAgents;
    }
    public ArrayList<String> getGlobalContentFromAgents() {
        return globalContentFromAgents;
    }

}
