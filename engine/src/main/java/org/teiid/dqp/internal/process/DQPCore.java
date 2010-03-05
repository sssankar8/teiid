/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.dqp.internal.process;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.resource.spi.work.Work;
import javax.resource.spi.work.WorkEvent;
import javax.resource.spi.work.WorkException;
import javax.resource.spi.work.WorkListener;
import javax.resource.spi.work.WorkManager;
import javax.transaction.xa.Xid;

import org.teiid.SecurityHelper;
import org.teiid.adminapi.Admin;
import org.teiid.adminapi.AdminException;
import org.teiid.adminapi.impl.RequestMetadata;
import org.teiid.adminapi.impl.SessionMetadata;
import org.teiid.adminapi.impl.WorkerPoolStatisticsMetadata;
import org.teiid.dqp.internal.cache.DQPContextCache;
import org.teiid.dqp.internal.datamgr.impl.ConnectorManagerRepository;

import com.metamatrix.api.exception.MetaMatrixComponentException;
import com.metamatrix.api.exception.MetaMatrixProcessingException;
import com.metamatrix.api.exception.query.QueryMetadataException;
import com.metamatrix.api.exception.security.SessionServiceException;
import com.metamatrix.common.buffer.BufferManager;
import com.metamatrix.common.comm.api.ResultsReceiver;
import com.metamatrix.common.lob.LobChunk;
import com.metamatrix.common.log.LogManager;
import com.metamatrix.common.queue.StatsCapturingWorkManager;
import com.metamatrix.common.types.Streamable;
import com.metamatrix.common.xa.MMXid;
import com.metamatrix.common.xa.XATransactionException;
import com.metamatrix.core.MetaMatrixRuntimeException;
import com.metamatrix.core.log.MessageLevel;
import com.metamatrix.core.util.Assertion;
import com.metamatrix.dqp.DQPPlugin;
import com.metamatrix.dqp.client.ClientSideDQP;
import com.metamatrix.dqp.client.MetadataResult;
import com.metamatrix.dqp.client.ResultsFuture;
import com.metamatrix.dqp.message.AtomicRequestMessage;
import com.metamatrix.dqp.message.RequestID;
import com.metamatrix.dqp.message.RequestMessage;
import com.metamatrix.dqp.message.ResultsMessage;
import com.metamatrix.dqp.service.AuthorizationService;
import com.metamatrix.dqp.service.BufferService;
import com.metamatrix.dqp.service.CommandLogMessage;
import com.metamatrix.dqp.service.TransactionContext;
import com.metamatrix.dqp.service.TransactionService;
import com.metamatrix.dqp.util.LogConstants;
import com.metamatrix.platform.security.api.service.SessionService;
import com.metamatrix.query.metadata.QueryMetadataInterface;
import com.metamatrix.query.processor.ProcessorDataManager;
import com.metamatrix.query.tempdata.TempTableStoreImpl;

/**
 * Implements the core DQP processing.
 */
public class DQPCore implements ClientSideDQP {
	
	private final static class FutureWork<T> implements Work, WorkListener {
		private final ResultsReceiver<T> receiver;
		private final Callable<T> toCall;

		private FutureWork(ResultsReceiver<T> receiver,
				Callable<T> processor) {
			this.receiver = receiver;
			this.toCall = processor;
		}

		@Override
		public void run() {
			try {
				receiver.receiveResults(toCall.call());
			} catch (Throwable t) {
				receiver.exceptionOccurred(t);
			}
		}

		@Override
		public void release() {
			
		}
		
		@Override
		public void workAccepted(WorkEvent arg0) {
			
		}
		
		@Override
		public void workCompleted(WorkEvent arg0) {
			
		}
		
		@Override
		public void workRejected(WorkEvent arg0) {
			receiver.exceptionOccurred(arg0.getException());
		}
		
		@Override
		public void workStarted(WorkEvent arg0) {
			
		}
	}	
	
	static class ClientState {
		List<RequestID> requests;
		TempTableStoreImpl tempTableStoreImpl;
		
		public ClientState(TempTableStoreImpl tableStoreImpl) {
			this.tempTableStoreImpl = tableStoreImpl;
		}
		
		public synchronized void addRequest(RequestID requestID) {
			if (requests == null) {
				requests = new LinkedList<RequestID>();
			}
			requests.add(requestID);
		}
		
		public synchronized List<RequestID> getRequests() {
			if (requests == null) {
				return Collections.emptyList();
			}
			return new ArrayList<RequestID>(requests);
		}

		public synchronized void removeRequest(RequestID requestID) {
			if (requests != null) {
				requests.remove(requestID);
			}
		}
		
	}
	
    private WorkManager workManager;
    private StatsCapturingWorkManager processWorkerPool;
    
    // System properties for Code Table
    private int maxCodeTableRecords = DQPConfiguration.DEFAULT_MAX_CODE_TABLE_RECORDS;
    private int maxCodeTables = DQPConfiguration.DEFAULT_MAX_CODE_TABLES;
    private int maxCodeRecords = DQPConfiguration.DEFAULT_MAX_CODE_RECORDS;
    
    private int maxFetchSize = DQPConfiguration.DEFAULT_FETCH_SIZE;
    
    // Resources
    private BufferManager bufferManager;
    private ProcessorDataManager dataTierMgr;
    private SessionAwareCache<PreparedPlan> prepPlanCache;
    private SessionAwareCache<CachedResults> rsCache;
    private TransactionService transactionService;
    private AuthorizationService authorizationService;
    private BufferService bufferService;
    private SessionService sessionService;
    private ConnectorManagerRepository connectorManagerRepository;
    
    // Query worker pool for processing plans
    private int processorTimeslice = DQPConfiguration.DEFAULT_PROCESSOR_TIMESLICE;
    private boolean processorDebugAllowed;
    
    private int chunkSize = Streamable.STREAMING_BATCH_SIZE_IN_BYTES;
    
	private Map<RequestID, RequestWorkItem> requests = new ConcurrentHashMap<RequestID, RequestWorkItem>();			
	private Map<String, ClientState> clientState = Collections.synchronizedMap(new HashMap<String, ClientState>());
	private DQPContextCache contextCache;
	private SecurityHelper securityHelper;
    
    /**
     * perform a full shutdown and wait for 10 seconds for all threads to finish
     */
    public void stop() {
    	processWorkerPool.shutdownNow();
    	try {
			processWorkerPool.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
		}
    	// TODO: Should we be doing more cleanup here??
		LogManager.logDetail(LogConstants.CTX_DQP, "Stopping the DQP"); //$NON-NLS-1$
    }
    
    /**
     * Return a list of {@link RequestMetadata} for the given session
     */
    public List<RequestMetadata> getRequestsForSession(long sessionId) {
    	ClientState state = getClientState(String.valueOf(sessionId), false);
    	if (state == null) {
    		return Collections.emptyList();
    	}
        return buildRequestInfos(state.getRequests());
    }
    
    public ClientState getClientState(String key, boolean create) {
    	synchronized (clientState) {
    		ClientState state = clientState.get(key);
    		if (state == null && create) {
    			state = new ClientState(new TempTableStoreImpl(bufferManager, key, null));
        		clientState.put(key, state);
    		}
    		return state;
		}
    }

    /**
     * Return a list of all {@link RequestMetadata} 
     */
    public List<RequestMetadata> getRequests() {
		return buildRequestInfos(requests.keySet());
    } 

    private List<RequestMetadata> buildRequestInfos(Collection<RequestID> ids) {
		List<RequestMetadata> results = new ArrayList<RequestMetadata>();
    	
		for (RequestID requestID : ids) {
            RequestWorkItem holder = requests.get(requestID);
            
            if(holder != null && !holder.isCanceled()) {
            	RequestMetadata req = new RequestMetadata();
            	
            	req.setExecutionId(holder.requestID.getExecutionID());
            	req.setSessionId(Long.parseLong(holder.requestID.getConnectionID()));
            	req.setCommand(holder.requestMsg.getCommandString());
            	req.setProcessingTime(holder.getProcessingTimestamp());
            	
            	if (holder.getTransactionContext() != null && holder.getTransactionContext().getXid() != null) {
            		req.setTransactionId(holder.getTransactionContext().getXid().toString());
            	}

                for (DataTierTupleSource conInfo : holder.getConnectorRequests()) {
                    String connectorName = conInfo.getConnectorName();

                    if (connectorName == null) {
                    	continue;
                    }
                    // If the request has not yet completed processing, then
                    // add all the subrequest messages
                	AtomicRequestMessage arm = conInfo.getAtomicRequestMessage();
                	RequestMetadata info = new RequestMetadata();
                	
                	info.setExecutionId(arm.getRequestID().getExecutionID());
                	info.setSessionId(Long.parseLong(holder.requestID.getConnectionID()));
                	info.setCommand(arm.getCommand().toString());
                	info.setProcessingTime(arm.getProcessingTimestamp());
                	info.setSourceRequest(true);
                	info.setNodeId(arm.getAtomicRequestID().getNodeID());
                	
        			results.add(info);
                }
                results.add(req);
            }
        }
    	return results;
	}    

	public ResultsFuture<ResultsMessage> executeRequest(long reqID,RequestMessage requestMsg) {
    	DQPWorkContext workContext = DQPWorkContext.getWorkContext();
		RequestID requestID = workContext.getRequestID(reqID);
		requestMsg.setFetchSize(Math.min(requestMsg.getFetchSize(), maxFetchSize));
		Request request = null;
	    if ( requestMsg.isPreparedStatement() || requestMsg.isCallableStatement()) {
	    	request = new PreparedStatementRequest(prepPlanCache);
	    } else {
	    	request = new Request();
	    }
	    ClientState state = this.getClientState(workContext.getConnectionID(), true);
	    request.initialize(requestMsg, bufferManager,
				dataTierMgr, transactionService, authorizationService, processorDebugAllowed,
				state.tempTableStoreImpl, workContext,
				chunkSize, connectorManagerRepository);
		
        ResultsFuture<ResultsMessage> resultsFuture = new ResultsFuture<ResultsMessage>();
        RequestWorkItem workItem = new RequestWorkItem(this, requestMsg, request, resultsFuture.getResultsReceiver(), requestID, workContext);
    	logMMCommand(workItem, true, false, 0); 
        addRequest(requestID, workItem, state);
        
        this.addWork(workItem);      
        return resultsFuture;
    }
	
	public ResultsFuture<ResultsMessage> processCursorRequest(long reqID,
			int batchFirst, int fetchSize) throws MetaMatrixProcessingException {
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
            LogManager.logDetail(LogConstants.CTX_DQP, "DQP process cursor request from " + batchFirst);  //$NON-NLS-1$
        }
		DQPWorkContext workContext = DQPWorkContext.getWorkContext();
        ResultsFuture<ResultsMessage> resultsFuture = new ResultsFuture<ResultsMessage>();
		RequestWorkItem workItem = getRequestWorkItem(workContext.getRequestID(reqID));
		workItem.requestMore(batchFirst, batchFirst + Math.min(fetchSize, maxFetchSize) - 1, resultsFuture.getResultsReceiver());
		return resultsFuture;
	}

	void addRequest(RequestID requestID, RequestWorkItem workItem, ClientState state) {
		this.requests.put(requestID, workItem);
		state.addRequest(requestID);
	}
    
    void removeRequest(final RequestWorkItem workItem) {
    	this.requests.remove(workItem.requestID);
    	ClientState state = getClientState(workItem.getDqpWorkContext().getConnectionID(), false);
    	if (state != null) {
    		state.removeRequest(workItem.requestID);
    	}
    	contextCache.removeRequestScopedCache(workItem.requestID.toString());
    }
           
    void addWork(Work work) {
    	try {
			this.processWorkerPool.scheduleWork(this.workManager, work);
		} catch (WorkException e) {
			//TODO: cancel? close?
			throw new MetaMatrixRuntimeException(e);
		}
    }
    
    public void setWorkManager(WorkManager mgr) {
    	this.workManager = mgr;
    }
    
    SecurityHelper getSecurityHelper() {
    	return this.securityHelper;
    }
    
    public void setSecurityHelper(SecurityHelper securityHelper) {
		this.securityHelper = securityHelper;
	}
    
	public ResultsFuture<?> closeLobChunkStream(int lobRequestId,
			long requestId, String streamId)
			throws MetaMatrixProcessingException {
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
            LogManager.logDetail(LogConstants.CTX_DQP, "Request to close the Lob stream with Stream id="+streamId+" instance id="+lobRequestId);  //$NON-NLS-1$//$NON-NLS-2$
        }   
        DQPWorkContext workContext = DQPWorkContext.getWorkContext();
        RequestWorkItem workItem = safeGetWorkItem(workContext.getRequestID(requestId));
        if (workItem != null) {
	        workItem.removeLobStream(lobRequestId);
        }
        return ResultsFuture.NULL_FUTURE;
    }
	    
	public ResultsFuture<LobChunk> requestNextLobChunk(int lobRequestId,
			long requestId, String streamId)
			throws MetaMatrixProcessingException {
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
            LogManager.logDetail(LogConstants.CTX_DQP, "Request for next Lob chunk with Stream id="+streamId+" instance id="+lobRequestId);  //$NON-NLS-1$//$NON-NLS-2$
        }  
        RequestWorkItem workItem = getRequestWorkItem(DQPWorkContext.getWorkContext().getRequestID(requestId));
        ResultsFuture<LobChunk> resultsFuture = new ResultsFuture<LobChunk>();
        workItem.processLobChunkRequest(streamId, lobRequestId, resultsFuture.getResultsReceiver());
        return resultsFuture;
    }
    
//    /**
//     * Cancels a node in the request. (This request is called by the 
//     * client directly using the admin API), so if this does not support
//     * partial results then remove the original request.
//     * @throws MetaMatrixComponentException 
//     */
//    public void cancelAtomicRequest(AtomicRequestID requestID) throws MetaMatrixComponentException {                    
//        RequestWorkItem workItem = safeGetWorkItem(requestID.getRequestID());
//        if (workItem == null) {
//    		LogManager.logDetail(LogConstants.CTX_DQP, "Could not cancel", requestID, "parent request does not exist"); //$NON-NLS-1$ //$NON-NLS-2$
//        	return;
//        }
//        workItem.requestAtomicRequestCancel(requestID);
//    }
    
    RequestWorkItem getRequestWorkItem(RequestID reqID) throws MetaMatrixProcessingException {
    	RequestWorkItem result = this.requests.get(reqID);
    	if (result == null) {
    		throw new MetaMatrixProcessingException(DQPPlugin.Util.getString("DQPCore.The_request_has_been_closed.", reqID));//$NON-NLS-1$
    	}
    	return result;
    }
    
	RequestWorkItem safeGetWorkItem(Object processorID) {
    	return this.requests.get(processorID);
	}
	
    /**
     * Returns a list of QueueStats objects that represent the queues in
     * this service.
     * If there are no queues, an empty Collection is returned.
     */
    public WorkerPoolStatisticsMetadata getWorkManagerStatistics() {
        return processWorkerPool.getStats();
    }
           
    public void terminateSession(long terminateeId) {
    	String sessionId = String.valueOf(terminateeId);
    	
        // sometimes there will not be any atomic requests pending, in that
        // situation we still need to clear the master request from our map
        ClientState state = getClientState(sessionId, false);
        if (state != null) {
	        for (RequestID reqId : state.getRequests()) {
	            try {
	                cancelRequest(reqId);
	            } catch (MetaMatrixComponentException err) {
	                LogManager.logWarning(LogConstants.CTX_DQP, err, "Failed to cancel " + reqId); //$NON-NLS-1$
				}
	        }
        }
        
        if (transactionService != null) {
            try {
                transactionService.cancelTransactions(sessionId, false);
            } catch (XATransactionException err) {
                LogManager.logWarning(LogConstants.CTX_DQP, "rollback failed for requestID=" + sessionId); //$NON-NLS-1$
            } 
        }
        contextCache.removeSessionScopedCache(sessionId);
    }

    public boolean cancelRequest(long sessionId, long requestId) throws MetaMatrixComponentException {
    	RequestID requestID = new RequestID(String.valueOf(sessionId), requestId);
    	return cancelRequest(requestID);
    }
    
    private boolean cancelRequest(RequestID requestID) throws MetaMatrixComponentException {
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
            LogManager.logDetail(LogConstants.CTX_DQP, "cancelQuery for requestID=" + requestID); //$NON-NLS-1$
        }
        
        boolean markCancelled = false;
        
        RequestWorkItem workItem = safeGetWorkItem(requestID);
        if (workItem != null) {
        	markCancelled = workItem.requestCancel();
        }
    	if (markCancelled) {
            logMMCommand(workItem, false, true, 0);
    	} else {
    		LogManager.logDetail(LogConstants.CTX_DQP, DQPPlugin.Util.getString("DQPCore.failed_to_cancel")); //$NON-NLS-1$
    	}
        return markCancelled;
    }
    
	public ResultsFuture<?> closeRequest(long requestId) throws MetaMatrixProcessingException, MetaMatrixComponentException {
        DQPWorkContext workContext = DQPWorkContext.getWorkContext();
        closeRequest(workContext.getRequestID(requestId));
        return ResultsFuture.NULL_FUTURE;
	}
    
    /**
     * Close the request with given ID 
     * @param requestID
     * @throws MetaMatrixComponentException 
     */
    void closeRequest(RequestID requestID) throws MetaMatrixComponentException {
        if (LogManager.isMessageToBeRecorded(LogConstants.CTX_DQP, MessageLevel.DETAIL)) {
            LogManager.logDetail(LogConstants.CTX_DQP, "closeQuery for requestID=" + requestID); //$NON-NLS-1$
        }
        
        RequestWorkItem workItem = safeGetWorkItem(requestID);
        if (workItem != null) {
        	workItem.requestClose();
        } else {
        	LogManager.logDetail(LogConstants.CTX_DQP, requestID + " close call ignored as the request has already been removed."); //$NON-NLS-1$
        }
    }
    
    private void clearPlanCache(){
        LogManager.logInfo(LogConstants.CTX_DQP, DQPPlugin.Util.getString("DQPCore.Clearing_prepared_plan_cache")); //$NON-NLS-1$
        this.prepPlanCache.clearAll();
    }

    private void clearCodeTableCache(){
        LogManager.logInfo(LogConstants.CTX_DQP, DQPPlugin.Util.getString("DQPCore.Clearing_code_table_cache")); //$NON-NLS-1$
        this.dataTierMgr.clearCodeTables();
    }
    
	private void clearResultSetCache() {
		//clear cache in server
		if(rsCache != null){
			rsCache.clearAll();
		}
	}
	
	
    public Collection<String> getCacheTypes(){
    	ArrayList<String> caches = new ArrayList<String>();
    	caches.add(Admin.Cache.CODE_TABLE_CACHE.toString());
    	caches.add(Admin.Cache.PREPARED_PLAN_CACHE.toString());
    	caches.add(Admin.Cache.CONNECTOR_RESULT_SET_CACHE.toString());
    	caches.add(Admin.Cache.QUERY_SERVICE_RESULT_SET_CACHE.toString());
    	return caches;
    }	
	
	public void clearCache(String cacheType) {
		Admin.Cache cache = Admin.Cache.valueOf(cacheType);
		switch (cache) {
		case CODE_TABLE_CACHE:
			clearCodeTableCache();
			break;
		case PREPARED_PLAN_CACHE:
			clearPlanCache();
			break;
		case CONNECTOR_RESULT_SET_CACHE:
			clearResultSetCache();
			break;
		case QUERY_SERVICE_RESULT_SET_CACHE:
			break;
		}
	}
    
	public Collection<SessionMetadata> getActiveSessions() throws SessionServiceException {
		if (this.sessionService == null) {
			return Collections.emptyList();
		}
		return this.sessionService.getActiveSessions();
	}
	
	public int getActiveSessionsCount() throws SessionServiceException{
		if (this.sessionService == null) {
			return 0;
		}
		return this.sessionService.getActiveSessionsCount();
	}	
	
	public Collection<org.teiid.adminapi.Transaction> getTransactions() {
		if (this.transactionService == null) {
			return Collections.emptyList();
		}
		return this.transactionService.getTransactions();
	}
	
	public void terminateTransaction(String xid) throws AdminException {
		if (this.transactionService == null) {
			return;
		}
		this.transactionService.terminateTransaction(xid);
	}	
	
    void logMMCommand(RequestWorkItem workItem, boolean isBegin, boolean isCancel, int rowCount) {
    	if (!LogManager.isMessageToBeRecorded(LogConstants.CTX_COMMANDLOGGING, MessageLevel.INFO)) {
    		return;
    	}
    	
        RequestMessage msg = workItem.requestMsg;
        DQPWorkContext workContext = DQPWorkContext.getWorkContext();
        RequestID rID = new RequestID(workContext.getConnectionID(), msg.getExecutionId());
        String command = null;
    	if(isBegin && !isCancel){
    		command = msg.getCommandString();
    	}
    	String txnID = null;
		TransactionContext tc = workItem.getTransactionContext();
		if (tc != null && tc.getXid() != null) {
			txnID = tc.getXid().toString();
		}
    	String appName = workContext.getAppName();
        // Log to request log
        short point = isBegin? CommandLogMessage.CMD_POINT_BEGIN:CommandLogMessage.CMD_POINT_END;
        short status = CommandLogMessage.CMD_STATUS_NEW;
        if(!isBegin){
        	if(isCancel){
        		status = CommandLogMessage.CMD_STATUS_CANCEL;
        	}else{
        		status = CommandLogMessage.CMD_STATUS_END;
        	}
        }
        CommandLogMessage message = null;
        if (point == CommandLogMessage.CMD_POINT_BEGIN) {
            message = new CommandLogMessage(System.currentTimeMillis(), rID.toString(), txnID, workContext.getConnectionID(), appName, workContext.getUserName(), workContext.getVdbName(), workContext.getVdbVersion(), command);
        } else {
            boolean isCancelled = false;
            boolean errorOccurred = false;

            if (status == CommandLogMessage.CMD_STATUS_CANCEL) {
                isCancelled = true;
            } else if (status == CommandLogMessage.CMD_STATUS_ERROR) {
                errorOccurred = true;
            }
            message = new CommandLogMessage(System.currentTimeMillis(), rID.toString(), txnID, workContext.getConnectionID(), workContext.getUserName(), workContext.getVdbName(), workContext.getVdbVersion(), rowCount, isCancelled, errorOccurred);
        }
        LogManager.log(MessageLevel.DETAIL, LogConstants.CTX_COMMANDLOGGING, message);
    }
    
    ProcessorDataManager getDataTierManager() {
    	return this.dataTierMgr;
    }
    
    public void setDataTierManager(ProcessorDataManager dataTierMgr) {
    	this.dataTierMgr = dataTierMgr;
    }

	BufferManager getBufferManager() {
		return bufferManager;
	}

	public TransactionService getTransactionService() {
		if (transactionService == null) {
			throw new MetaMatrixRuntimeException("Transactions are not enabled"); //$NON-NLS-1$
		}
		return transactionService;
	}

	SessionAwareCache<CachedResults> getRsCache() {
		return rsCache;
	}
	
	int getProcessorTimeSlice() {
		return this.processorTimeslice;
	}	
	
	int getChunkSize() {
		return chunkSize;
	}
	
	public void start(DQPConfiguration config) {
		
		Assertion.isNotNull(this.workManager);

		this.processorTimeslice = config.getTimeSliceInMilli();
        this.maxFetchSize = config.getMaxRowsFetchSize();
        this.processorDebugAllowed = config.isProcessDebugAllowed();
        this.maxCodeTableRecords = config.getCodeTablesMaxRowsPerTable();
        this.maxCodeTables = config.getCodeTablesMaxCount();
        this.maxCodeRecords = config.getCodeTablesMaxRows();
        
        this.chunkSize = config.getLobChunkSizeInKB() * 1024;
        
        //result set cache
        if (config.isResultSetCacheEnabled()) {
			this.rsCache = new SessionAwareCache<CachedResults>(config.getResultSetCacheMaxEntries());
        }

        //prepared plan cache
        prepPlanCache = new SessionAwareCache<PreparedPlan>(config.getPreparedPlanCacheMaxCount());
		
        // Processor debug flag
        LogManager.logInfo(LogConstants.CTX_DQP, DQPPlugin.Util.getString("DQPCore.Processor_debug_allowed_{0}", this.processorDebugAllowed)); //$NON-NLS-1$
                        
        //get buffer manager
        this.bufferManager = bufferService.getBufferManager();
        this.contextCache = bufferService.getContextCache();

        this.processWorkerPool = new StatsCapturingWorkManager(DQPConfiguration.PROCESS_PLAN_QUEUE_NAME, config.getMaxThreads());
        
        dataTierMgr = new DataTierManagerImpl(this,
                                            this.connectorManagerRepository,
                                            this.bufferService,
                                            this.workManager,
                                            this.maxCodeTables,
                                            this.maxCodeRecords,
                                            this.maxCodeTableRecords);    

	}
	
	public void setBufferService(BufferService service) {
		this.bufferService = service;
		setContextCache(service.getContextCache());
	}
	
	public void setAuthorizationService(AuthorizationService service) {
		this.authorizationService = service;
	}

	public void setContextCache(DQPContextCache cache) {
		this.contextCache = cache;
	}

	public void setTransactionService(TransactionService service) {
		this.transactionService = service;
	}
	
	public List<String> getXmlSchemas(String docName) throws MetaMatrixComponentException, QueryMetadataException {
		DQPWorkContext workContext = DQPWorkContext.getWorkContext();
        QueryMetadataInterface metadata = workContext.getVDB().getAttachment(QueryMetadataInterface.class);

        Object groupID = metadata.getGroupID(docName);
        return metadata.getXMLSchemas(groupID);
	}

	@Override
	public boolean cancelRequest(long requestID)
			throws MetaMatrixProcessingException, MetaMatrixComponentException {
		DQPWorkContext workContext = DQPWorkContext.getWorkContext();
		return this.cancelRequest(workContext.getRequestID(requestID));
	}
	
	// local txn
	public ResultsFuture<?> begin() throws XATransactionException {
    	String threadId = DQPWorkContext.getWorkContext().getConnectionID();
    	this.getTransactionService().begin(threadId);
    	return ResultsFuture.NULL_FUTURE;
	}
	
	// local txn
	public ResultsFuture<?> commit() throws XATransactionException {
		final String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		Callable<Void> processor = new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				getTransactionService().commit(threadId);
				return null;
			}
		};
		return addWork(processor);
	}
	
	// local txn
	public ResultsFuture<?> rollback() throws XATransactionException {
		final String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		Callable<Void> processor = new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				getTransactionService().rollback(threadId);
				return null;
			}
		};
		return addWork(processor);
	}

	// global txn
	public ResultsFuture<?> commit(final MMXid xid, final boolean onePhase) throws XATransactionException {
		final String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		Callable<Void> processor = new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				getTransactionService().commit(threadId, xid, onePhase, false);
				return null;
			}
		};
		return addWork(processor);
	}
	// global txn
	public ResultsFuture<?> end(MMXid xid, int flags) throws XATransactionException {
		String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		this.getTransactionService().end(threadId, xid, flags, false);
		return ResultsFuture.NULL_FUTURE;
	}
	// global txn
	public ResultsFuture<?> forget(MMXid xid) throws XATransactionException {
		String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		this.getTransactionService().forget(threadId, xid, false);
		return ResultsFuture.NULL_FUTURE;
	}
		
	// global txn
	public ResultsFuture<Integer> prepare(final MMXid xid) throws XATransactionException {
		Callable<Integer> processor = new Callable<Integer>() {
			@Override
			public Integer call() throws Exception {
				return getTransactionService().prepare(DQPWorkContext.getWorkContext().getConnectionID(),xid, false);
			}
		};
		return addWork(processor);
	}

	private <T> ResultsFuture<T> addWork(Callable<T> processor) {
		ResultsFuture<T> result = new ResultsFuture<T>();
		ResultsReceiver<T> receiver = result.getResultsReceiver();
		try {
			this.workManager.scheduleWork(new FutureWork<T>(receiver, processor));
		} catch (WorkException e) {
			throw new MetaMatrixRuntimeException(e);
		}
		return result;
	}
	
	// global txn
	public ResultsFuture<Xid[]> recover(int flag) throws XATransactionException {
		ResultsFuture<Xid[]> result = new ResultsFuture<Xid[]>();
		result.getResultsReceiver().receiveResults(this.getTransactionService().recover(flag, false));
		return result;
	}
	// global txn
	public ResultsFuture<?> rollback(final MMXid xid) throws XATransactionException {
		Callable<Void> processor = new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				getTransactionService().rollback(DQPWorkContext.getWorkContext().getConnectionID(),xid, false);
				return null;
			}
		};
		return addWork(processor);
	}
	// global txn
	public ResultsFuture<?> start(MMXid xid, int flags, int timeout)
			throws XATransactionException {
		String threadId = DQPWorkContext.getWorkContext().getConnectionID();
		this.getTransactionService().start(threadId, xid, flags, timeout, false);
		return ResultsFuture.NULL_FUTURE;
	}

	public MetadataResult getMetadata(long requestID)
			throws MetaMatrixComponentException, MetaMatrixProcessingException {
		DQPWorkContext workContext = DQPWorkContext.getWorkContext();
		MetaDataProcessor processor = new MetaDataProcessor(this, this.prepPlanCache,  workContext.getVdbName(), workContext.getVdbVersion());
		return processor.processMessage(workContext.getRequestID(requestID), workContext, null, true);
	}

	public MetadataResult getMetadata(long requestID, String preparedSql,
			boolean allowDoubleQuotedVariable)
			throws MetaMatrixComponentException, MetaMatrixProcessingException {
		DQPWorkContext workContext = DQPWorkContext.getWorkContext();
		MetaDataProcessor processor = new MetaDataProcessor(this, this.prepPlanCache, workContext.getVdbName(), workContext.getVdbVersion());
		return processor.processMessage(workContext.getRequestID(requestID), workContext, preparedSql, allowDoubleQuotedVariable);
	}
	
	public void setConnectorManagerRepository(ConnectorManagerRepository repo) {
		this.connectorManagerRepository = repo;
	}
	
	public ConnectorManagerRepository getConnectorManagerRepository() {
		return this.connectorManagerRepository;
	}
	
	public void setSessionService(SessionService service) {
		this.sessionService = service;
		service.setDqp(this);
	}
	
	public SessionService getSessionService() {
		return this.sessionService;
	}
	
	public AuthorizationService getAuthorizationService() {
		return this.authorizationService;
	}
}