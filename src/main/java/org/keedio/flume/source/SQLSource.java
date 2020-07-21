/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.keedio.flume.source;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.conf.Configurable;
import org.apache.flume.event.SimpleEvent;
import org.apache.flume.source.AbstractSource;
import org.keedio.flume.metrics.SqlSourceCounter;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVWriter;

/*Support UTF-8 character encoding.*/
import com.google.common.base.Charsets;
import java.nio.charset.Charset;


/**
 * A Source to read data from a SQL database. This source ask for new data in a table each configured time.<p>
 * 
 * @author <a href="mailto:mvalle@keedio.com">Marcelo Valle</a>
 */
public class SQLSource extends AbstractSource implements Configurable, PollableSource {
    @Override
    public long getBackOffSleepIncrement() {
        return 0;
    }

    @Override
    public long getMaxBackOffSleepInterval() {
        return 0;
    }

    private static final Logger LOG = LoggerFactory.getLogger(SQLSource.class);
    protected SQLSourceHelper sqlSourceHelper;
    private SqlSourceCounter sqlSourceCounter;
    private CSVWriter csvWriter;
    private HibernateHelper hibernateHelper;
    // TODO
    Map<String, String> jobStatusMap = new HashMap<String, String>();
       
    /**
     * Configure the source, load configuration properties and establish connection with database
     */
    @Override
    public void configure(Context context) {
    	
    	LOG.getName();
        	
    	LOG.info("Reading and processing configuration values for source " + getName());
		
    	/* Initialize configuration parameters */
    	sqlSourceHelper = new SQLSourceHelper(context, this.getName());
        
    	/* Initialize metric counters */
		sqlSourceCounter = new SqlSourceCounter("SOURCESQL." + this.getName());
        
        /* Establish connection with database */
        hibernateHelper = new HibernateHelper(sqlSourceHelper);
        hibernateHelper.establishSession();
       
        /* Instantiate the CSV Writer */
        csvWriter = new CSVWriter(new ChannelWriter(),sqlSourceHelper.getDelimiterEntry().charAt(0));
        
    }  
    
    /**
     * Process a batch of events performing SQL Queries
     */
	@Override
	public Status process() throws EventDeliveryException {
		
		try {
			sqlSourceCounter.startProcess();			
			
			List<List<Object>> result = hibernateHelper.executeQuery();
						
			if (!result.isEmpty())
			{
                /**
                 * 将状态为S的JOB计入Map
                 */
                List<String[]> allRows = sqlSourceHelper.getAllRows(result);
                LOG.debug("allRows.size() = " + allRows.size());
                for (String[] row: allRows) {
                    LOG.debug(Arrays.toString(row));
                    if (row.length > 4 && row[4].contains("S")) {
                        String instExec = row[1];
                        String jobStatus = row[4];
                        if (!jobStatusMap.containsKey(instExec)) {
                            jobStatusMap.put(instExec, jobStatus);
                        }
                    }
                }
				csvWriter.writeAll(allRows,sqlSourceHelper.encloseByQuotes());
				csvWriter.flush();
				sqlSourceCounter.incrementEventCount(result.size());
				
				sqlSourceHelper.updateStatusFile();
			}
			
			sqlSourceCounter.endProcess(result.size());
			
			if (result.size() < sqlSourceHelper.getMaxRows()){
				Thread.sleep(sqlSourceHelper.getRunQueryDelay());
			}

            /**
             * 将Map中的状态为S数据反复查询确认直到状态产生变化传送到channel
             */
            Log.debug("Jos Status Map NUM = " + jobStatusMap.size());
            List<String[]> updatedRows = new ArrayList<String[]>();
            String currentIndex = sqlSourceHelper.getCurrentIndex();
            sqlSourceHelper.setCurrentIndex("-1");
            Iterator<Map.Entry<String, String>> it = jobStatusMap.entrySet().iterator();
            while(it.hasNext()){
                Map.Entry<String, String> entry = it.next();
                List<List<Object>> resultForDataUpdated = null;
                try {
                    resultForDataUpdated = hibernateHelper.executeQueryForDataUpdated("INST_EXEC", entry.getKey());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                List<String[]> allRowsForDataUpdated = sqlSourceHelper.getAllRows(resultForDataUpdated);
                if (allRowsForDataUpdated.size() > 0 && !allRowsForDataUpdated.get(0)[4].contains("S")) {
                    updatedRows.add(allRowsForDataUpdated.get(0));
                    it.remove();
                }
            }
            sqlSourceHelper.setCurrentIndex(currentIndex);
            if (updatedRows.size() > 0) {
                csvWriter.writeAll(updatedRows,sqlSourceHelper.encloseByQuotes());
                csvWriter.flush();
            }

            return Status.READY;
			
		} catch (IOException | InterruptedException e) {
			LOG.error("Error procesing row", e);
			return Status.BACKOFF;
		}
	}
 
	/**
	 * Starts the source. Starts the metrics counter.
	 */
	@Override
    public void start() {
        
    	LOG.info("Starting sql source {} ...", getName());
        sqlSourceCounter.start();
        super.start();
    }

	/**
	 * Stop the source. Close database connection and stop metrics counter.
	 */
    @Override
    public void stop() {
        
        LOG.info("Stopping sql source {} ...", getName());
        
        try 
        {
            hibernateHelper.closeSession();
            csvWriter.close();    
        } catch (IOException e) {
        	LOG.warn("Error CSVWriter object ", e);
        } finally {
        	this.sqlSourceCounter.stop();
        	super.stop();
        }
    }
    
    private class ChannelWriter extends Writer{
        private List<Event> events = new ArrayList<>();

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            Event event = new SimpleEvent();
            
            String s = new String(cbuf);
            event.setBody(s.substring(off, len-1).getBytes(Charset.forName(sqlSourceHelper.getDefaultCharsetResultSet())));
            
            Map<String, String> headers;
            headers = new HashMap<String, String>();
			headers.put("timestamp", String.valueOf(System.currentTimeMillis()));
			event.setHeaders(headers);
			
            events.add(event);
            
            if (events.size() >= sqlSourceHelper.getBatchSize())
            	flush();
        }

        @Override
        public void flush() throws IOException {
            getChannelProcessor().processEventBatch(events);
            events.clear();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
