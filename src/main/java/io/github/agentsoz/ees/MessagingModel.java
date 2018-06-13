package io.github.agentsoz.ees;

/*
 * #%L
 * BDI-ABM Integration Package
 * %%
 * Copyright (C) 2014 - 2016 by its authors. See AUTHORS file.
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

import com.google.gson.Gson;
import io.github.agentsoz.dataInterface.DataServer;
import io.github.agentsoz.dataInterface.DataSource;
import io.github.agentsoz.util.EmergencyMessage;
import io.github.agentsoz.util.Time;
import io.github.agentsoz.util.evac.PerceptList;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class MessagingModel implements DataSource {

	private final Logger logger = LoggerFactory.getLogger("io.github.agentsoz.ees");

	private DataServer dataServer = null;
	private double lastUpdateTimeInMinutes = -1;
	private TreeMap<Double, EmergencyMessage> messages;
	private TreeMap<String, Double[][]> zones;

	private Time.TimestepUnit timestepUnit = Time.TimestepUnit.SECONDS;

	public MessagingModel() {

		messages = new TreeMap<>();
		zones = new TreeMap<>();

	}

	@SuppressWarnings("unchecked")
	void loadJsonMessagesForZones(String msgFile, String zonesFile) throws IOException, ParseException, java.text.ParseException {
		//First load the zones
		loadGeoJsonZones(zonesFile);
		// Then the messages
		Gson gson = new Gson();
		logger.info("Loading JSON messages file: " + msgFile);
		// Create the JSON parsor
		JSONParser parser = new JSONParser();
		// Read in the JSON file
		JSONObject json = (JSONObject) (parser.parse(new FileReader(msgFile)));
		JSONArray list = (JSONArray) json.get("messages");
		for (JSONObject object : (Iterable<JSONObject>) list) {
			// create the message object directly from JSON
			EmergencyMessage message = gson.fromJson(object.toJSONString(), EmergencyMessage.class);
			// convert the HHMM broadcast time to simulation time units
			DateFormat format = new SimpleDateFormat("HHmm", Locale.ENGLISH);
			Date date = format.parse(message.getBroadcastHHMM());
			Calendar cal = Calendar.getInstance();
			cal.setTime(date);
			double minutes = 60 * cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE);
			// get the enclosing polygon for each zone
			TreeMap<String,Double[][]> map = message.getBroadcastZones();
			for (String zoneId : map.keySet()) {
				if (zones.containsKey(zoneId)) {
					map.put(zoneId,zones.get(zoneId));
				}
			}
			message.setBroadcastZones(map);
			// save it
			messages.put(minutes, message);
		}
	}

	/**
	 * Expects a GeoJSON structure of the form:
	 * {@code
	 * {...,"features":[
	 *  {"properties":{"SA1_MAIN11": "id1",...},
	 *   "geometry":{"type":"Polygon","coordinates":[[ [lon1,lat1],[lon2,lat2] ]]}
	 *  },...]}
	 *
	 * @param file
	 * @throws FileNotFoundException
	 * @throws IOException
	 * @throws ParseException
	 * @throws java.text.ParseException
	 */
	@SuppressWarnings("unchecked")
	private void loadGeoJsonZones(String file) throws IOException, ParseException, java.text.ParseException {
		logger.info("Loading zones from GeoJSON file: " + file);
		// Create the JSON parsor
		JSONParser parser = new JSONParser();
		// Read in the JSON file
		JSONObject json = (JSONObject) (parser.parse(new FileReader(file)));
		// Loop through the features (which contains the time-stamped fire
		// shapes)
		JSONArray features = (JSONArray) json.get("features");
		for (JSONObject feature : (Iterable<JSONObject>) features) {
			JSONObject properties = (JSONObject) feature.get("properties");
			JSONObject geometry = (JSONObject) feature.get("geometry");
			JSONArray jcoords = (JSONArray) geometry.get("coordinates");
			JSONArray coords = (JSONArray) jcoords.get(0);
			Double[][] polygon = new Double[coords.size()][2];
			Iterator<JSONArray> it = coords.iterator();
			int i = 0;
			while (it.hasNext()) {
				polygon[i++] = (Double[]) it.next().toArray(new Double[2]);
			}
			String zoneId = (String) properties.get("SA1_MAIN11");
			zones.put(zoneId, polygon);
		}
	}

	@Override
	public Object getNewData(double timestep, Object parameters) {
		double time = Time.convertTime(timestep, timestepUnit, Time.TimestepUnit.MINUTES);
		SortedMap<Double, EmergencyMessage> effectiveMessages = messages.subMap(lastUpdateTimeInMinutes, time);
		lastUpdateTimeInMinutes = time;
		Double nextTime = messages.higherKey(time);
		if (nextTime != null) {
			dataServer.registerTimedUpdate(PerceptList.EMERGENCY_MESSAGE, this, Time.convertTime(nextTime, Time.TimestepUnit.MINUTES, timestepUnit));
		}
		return effectiveMessages;
	}

	/**
	 * Sets the publish/subscribe data server
	 * @param dataServer the server to use
	 */
	void setDataServer(DataServer dataServer) {
		this.dataServer = dataServer;
	}

	/**
	 * Start publishing messages data
	 */
	public void start(int[] hhmm) {
		double startTimeInSeconds = Time.convertTime(hhmm[0], Time.TimestepUnit.HOURS, Time.TimestepUnit.SECONDS)
				+ Time.convertTime(hhmm[1], Time.TimestepUnit.MINUTES, Time.TimestepUnit.SECONDS);
		dataServer.registerTimedUpdate(PerceptList.EMERGENCY_MESSAGE, this, Time.convertTime(startTimeInSeconds, Time.TimestepUnit.SECONDS, timestepUnit));
	}

	/**
	 * Set the time step unit for this model
	 * @param unit the time step unit to use
	 */
	void setTimestepUnit(Time.TimestepUnit unit) {
		timestepUnit = unit;
	}
}
