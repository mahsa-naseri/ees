/**
 * 
 */
package io.github.agentsoz.bushfire.matsimjill;

import java.util.List;
import java.util.SortedMap;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.utils.misc.CRCChecksum;
import org.matsim.testcases.MatsimTestUtils;

import io.github.agentsoz.bushfire.matsimjill.Main;
import io.github.agentsoz.util.TestUtils;
/**
 * @author dsingh
 *
 */
public class MainMaldon600Test {
	// have tests in separate classes so that they run, at least under maven, in separate JVMs.  kai, nov'17

	@Rule public MatsimTestUtils utils = new MatsimTestUtils() ;


	@SuppressWarnings("static-method")
	@Test
	@Ignore
	public void testMaldon600() {

		/*
--config /var/www/data/user-data/2017-10-23-ds-maldon/scenario/scenario_main.xml 
--logfile /var/www/data/user-data/2017-10-23-ds-maldon/scenario/scenario.log 
--loglevel TRACE 
--jillconfig "--config={agents:[{classname:io.github.agentsoz.bushfire.matsimjill.agents.Resident, args:null, count:600}],logLevel: WARN,logFile: \"/var/www/data/user-data/2017-10-23-ds-maldon/scenario/jill.log\",programOutputFile: \"/var/www/data/user-data/2017-10-23-ds-maldon/scenario/jill.out\"}"
		 */
		String [] args = {
				"--config",  "scenarios/maldon-2017-11-01/scenario_main.xml", 
				"--logfile", "scenarios/maldon-2017-11-01/scenario.log",
				"--loglevel", "INFO",
				//				"--plan-selection-policy", "FIRST", // ensures it is deterministic, as default is RANDOM
				"--seed", "12345",
				"--safeline-output-file-pattern", "scenarios/campbells-creek/safeline.%d%.out",
				"--matsim-output-directory", utils.getOutputDirectory(),
				"--jillconfig", "--config={"+
						"agents:[{classname:io.github.agentsoz.bushfire.matsimjill.agents.Resident, args:null, count:600}],"+
						"logLevel: WARN,"+
						"logFile: \"scenarios/maldon-2017-11-01/jill.log\","+
						"programOutputFile: \"scenarios/maldon-2017-11-01/jill.out\","+
						"randomSeed: 12345,"+ // jill random seed
						"numThreads: 1"+ // run jill in single-threaded mode so logs are deterministic
						"}"
		};

		Main.main(args);

		// print these so that we have them:
		final String actualEventsFilename = utils.getOutputDirectory() + "/output_events.xml.gz";
		long actualEventsCRC = CRCChecksum.getCRCFromFile( actualEventsFilename ) ;
		System.err.println("actual(events)="+actualEventsCRC) ;

		long actualPlansCRC = CRCChecksum.getCRCFromFile( utils.getOutputDirectory() + "/output_plans.xml.gz" ) ;
		System.err.println("actual(plans)="+actualPlansCRC) ;
		
		// look into events:

		final String primaryExpectedEventsFilename = utils.getInputDirectory() + "/output_events.xml.gz";

		SortedMap<Id<Person>, List<Double>> arrivalsExpected = TestUtils.collectArrivals(primaryExpectedEventsFilename) ;
		SortedMap<Id<Person>, List<Double>> arrivalsActual = TestUtils.collectArrivals( actualEventsFilename ) ;
		
		// test with slack:
		TestUtils.compareEventsWithSlack(arrivalsExpected, arrivalsActual, 600);
		
		// exact test, but sorting first:
//		TestUtils.compareFullEvents(primaryExpectedEventsFilename, actualEventsFilename);
		
		// comparing the plain files, but maybe more than one:
		long [] expectedEvents = new long [] {
				CRCChecksum.getCRCFromFile( primaryExpectedEventsFilename ), 
				3875322657L // travis
		} ;

		TestUtils.checkSeveral(expectedEvents, actualEventsCRC);

		// look into plans:

		long [] expectedPlans = new long [] {
				CRCChecksum.getCRCFromFile( utils.getInputDirectory() + "/output_plans.xml.gz" ),
				5242695L // travis
		} ;
		
		TestUtils.checkSeveral(expectedPlans, actualPlansCRC);



		// FIXME: still small differences in jill log. dsingh, 06/Nov/17
		// expectedCRC = CRCChecksum.getCRCFromFile( utils.getInputDirectory() + "/jill.out" ) ;
		// actualCRC = CRCChecksum.getCRCFromFile( "scenarios/maldon-2017-11-01/jill.out" ) ;
		// Assert.assertEquals (expectedCRC, actualCRC); 

	}
}
