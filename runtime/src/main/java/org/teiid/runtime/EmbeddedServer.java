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

package org.teiid.runtime;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.transaction.RollbackException;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.teiid.Replicated;
import org.teiid.Replicated.ReplicationMode;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.cache.Cache;
import org.teiid.cache.CacheConfiguration;
import org.teiid.cache.DefaultCacheFactory;
import org.teiid.cache.CacheConfiguration.Policy;
import org.teiid.client.DQP;
import org.teiid.client.security.ILogon;
import org.teiid.common.buffer.BufferManager;
import org.teiid.common.buffer.TupleBufferCache;
import org.teiid.core.TeiidRuntimeException;
import org.teiid.deployers.CompositeVDB;
import org.teiid.deployers.UDFMetaData;
import org.teiid.deployers.VDBLifeCycleListener;
import org.teiid.deployers.VDBRepository;
import org.teiid.deployers.VirtualDatabaseException;
import org.teiid.dqp.internal.datamgr.ConnectorManager;
import org.teiid.dqp.internal.datamgr.ConnectorManagerRepository;
import org.teiid.dqp.internal.datamgr.ConnectorManagerRepository.ConnectorManagerException;
import org.teiid.dqp.internal.datamgr.ConnectorManagerRepository.ExecutionFactoryProvider;
import org.teiid.dqp.internal.process.CachedResults;
import org.teiid.dqp.internal.process.DQPCore;
import org.teiid.dqp.internal.process.PreparedPlan;
import org.teiid.dqp.internal.process.SessionAwareCache;
import org.teiid.dqp.internal.process.TransactionServerImpl;
import org.teiid.dqp.service.BufferService;
import org.teiid.dqp.service.TransactionContext;
import org.teiid.dqp.service.TransactionContext.Scope;
import org.teiid.events.EventDistributor;
import org.teiid.events.EventDistributorFactory;
import org.teiid.jdbc.ConnectionImpl;
import org.teiid.jdbc.ConnectionProfile;
import org.teiid.jdbc.TeiidDriver;
import org.teiid.jdbc.TeiidSQLException;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.Datatype;
import org.teiid.metadata.MetadataFactory;
import org.teiid.metadata.MetadataRepository;
import org.teiid.metadata.MetadataStore;
import org.teiid.metadata.index.IndexMetadataStore;
import org.teiid.net.CommunicationException;
import org.teiid.net.ConnectionException;
import org.teiid.query.ObjectReplicator;
import org.teiid.query.function.SystemFunctionManager;
import org.teiid.query.metadata.TransformationMetadata;
import org.teiid.query.metadata.TransformationMetadata.Resource;
import org.teiid.query.tempdata.GlobalTableStore;
import org.teiid.query.tempdata.GlobalTableStoreImpl;
import org.teiid.services.AbstractEventDistributorFactoryService;
import org.teiid.services.BufferServiceImpl;
import org.teiid.services.SessionServiceImpl;
import org.teiid.translator.ExecutionFactory;
import org.teiid.translator.Translator;
import org.teiid.translator.TranslatorException;
import org.teiid.transport.ClientServiceRegistry;
import org.teiid.transport.ClientServiceRegistryImpl;
import org.teiid.transport.LocalServerConnection;
import org.teiid.transport.LogonImpl;

/**
 * A simplified server environment for embedded use.
 * 
 * Needs to be started prior to use with a call to {@link #start(EmbeddedConfiguration)}
 */
@SuppressWarnings("serial")
public class EmbeddedServer extends AbstractVDBDeployer implements EventDistributorFactory, ExecutionFactoryProvider {

	protected final class TransactionDetectingTransactionServer extends
			TransactionServerImpl {
		
		/**
		 * Override to detect existing thread bound transactions.
		 * This may be of interest for local connections as well, but
		 * we assume there that a managed datasource will be used.
		 * A managed datasource is not possible here.
		 */
		public TransactionContext getOrCreateTransactionContext(final String threadId) {
			TransactionContext tc = super.getOrCreateTransactionContext(threadId);
			if (useCallingThread && detectTransactions && tc.getTransaction() == null) {
				try {
					Transaction tx = transactionManager.getTransaction();
					if (tx != null) {
						tx.registerSynchronization(new Synchronization() {
							
							@Override
							public void beforeCompletion() {
							}
							
							@Override
							public void afterCompletion(int status) {
								transactions.removeTransactionContext(threadId);
							}
						});
						tc.setTransaction(tx);
						tc.setTransactionType(Scope.LOCAL);
					}
				} catch (SystemException e) {
				} catch (IllegalStateException e) {
				} catch (RollbackException e) {
				}
			}
			
			return tc;
		}
	}

	protected class ProviderAwareConnectorManagerRepository extends
			ConnectorManagerRepository {
		protected ConnectorManager createConnectorManager(
				String translatorName, String connectionName) {
			return new ConnectorManager(translatorName, connectionName) {
				@Override
				public Object getConnectionFactory() throws TranslatorException {
					ConnectionFactoryProvider<?> connectionFactoryProvider = connectionFactoryProviders.get(getConnectionName());
					if (connectionFactoryProvider != null) {
						return connectionFactoryProvider.getConnectionFactory();
					}
					return super.getConnectionFactory();
				}
			};
		}
	}

	public interface ConnectionFactoryProvider<T> {
		T getConnectionFactory() throws TranslatorException;
	}
	
	/**
	 * Annotated cache for use with the {@link EmbeddedServer} with an {@link ObjectReplicator} instead of Infinispan.
	 * @param <K> key
	 * @param <V> value
	 */
	public interface ReplicatedCache<K, V> extends Cache<K, V> {

		@Replicated(replicateState = ReplicationMode.PULL)
		public V get(K key);

		@Replicated(replicateState = ReplicationMode.PUSH)
		V put(K key, V value, Long ttl);

		@Replicated()
		V remove(K key);

	}
	
	protected DQPCore dqp = new DQPCore();
	protected VDBRepository repo = new VDBRepository();
	private ConcurrentHashMap<String, ExecutionFactory<?, ?>> translators = new ConcurrentHashMap<String, ExecutionFactory<?, ?>>();
	private ConcurrentHashMap<String, ConnectionFactoryProvider<?>> connectionFactoryProviders = new ConcurrentHashMap<String, ConnectionFactoryProvider<?>>();
	protected SessionServiceImpl sessionService = new SessionServiceImpl();
	protected ObjectReplicator replicator;
	protected BufferServiceImpl bufferService = new BufferServiceImpl();
	protected TransactionDetectingTransactionServer transactionService = new TransactionDetectingTransactionServer();
	protected ClientServiceRegistryImpl services = new ClientServiceRegistryImpl();
	protected LogonImpl logon;
	private TeiidDriver driver = new TeiidDriver();
	protected ConnectorManagerRepository cmr = new ProviderAwareConnectorManagerRepository();
	protected DefaultCacheFactory dcf = new DefaultCacheFactory() {
		
		List<ReplicatedCache<?, ?>> caches = new ArrayList<ReplicatedCache<?, ?>>();
		
		public boolean isReplicated() {
			return true;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <K, V> Cache<K, V> get(String location,
				CacheConfiguration config) {
			Cache<K, V> result = super.get(location, config);
			if (replicator != null) {
				try {
					ReplicatedCache cache = replicator.replicate("$RS$", ReplicatedCache.class, new ReplicatedCacheImpl(result), 0); //$NON-NLS-1$
					caches.add(cache);
					return cache;
				} catch (Exception e) {
					throw new TeiidRuntimeException(e);
				}
			}
			return result;
		}
		
		@Override
		public void destroy() {
			if (replicator != null) {
				for (ReplicatedCache<?, ?> cache : caches) {
					replicator.stop(cache);
				}
				caches.clear();
			}
			super.destroy();
		}
	};
	protected AbstractEventDistributorFactoryService eventDistributorFactoryService = new AbstractEventDistributorFactoryService() {
		
		@Override
		protected VDBRepository getVdbRepository() {
			return repo;
		}
		
		@Override
		protected ObjectReplicator getObjectReplicator() {
			return replicator;
		}
	};
	protected boolean useCallingThread = true;
	//TODO: allow for configurablity - in environments that support subtransations it would be fine
	//to allow teiid to start a request transaction under an existing thread bound transaction
	protected boolean detectTransactions = true;
	private Boolean running;
	
	public EmbeddedServer() {

	}

	public void addConnectionFactoryProvider(String name,
			ConnectionFactoryProvider<?> connectionFactoryProvider) {
		this.connectionFactoryProviders.put(name, connectionFactoryProvider);
	}

	public synchronized void start(EmbeddedConfiguration dqpConfiguration) {
		if (running != null) {
			throw new IllegalStateException();
		}
		this.eventDistributorFactoryService.start();
		this.dqp.setEventDistributor(this.eventDistributorFactoryService.getReplicatedEventDistributor());
		this.replicator = dqpConfiguration.getObjectReplicator();
		if (dqpConfiguration.getSystemStore() == null) {
			MetadataStore ms = new MetadataStore();
			try {
				IndexMetadataStore.loadSystemDatatypes(ms);
			} catch (IOException e) {
				throw new TeiidRuntimeException(e);
			}
			this.repo.setSystemStore(ms);
		} else {
			this.repo.setSystemStore(dqpConfiguration.getSystemStore());
		}
		if (dqpConfiguration.getTransactionManager() == null) {
			LogManager.logInfo(LogConstants.CTX_RUNTIME, RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40089));
			this.transactionService.setTransactionManager((TransactionManager) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] {TransactionManager.class}, new InvocationHandler() {
				
				@Override
				public Object invoke(Object proxy, Method method, Object[] args)
						throws Throwable {
					throw new UnsupportedOperationException(RuntimePlugin.Util.gs(RuntimePlugin.Event.TEIID40089));
				}
			}));
			this.detectTransactions = false;
		} else {
			this.transactionService.setTransactionManager(dqpConfiguration.getTransactionManager());
		}
		if (dqpConfiguration.getSecurityHelper() != null) {
			this.sessionService.setSecurityHelper(dqpConfiguration.getSecurityHelper());
		} else {
			this.sessionService.setSecurityHelper(new DoNothingSecurityHelper());
		}
		if (dqpConfiguration.getSecurityDomains() != null) {
			this.sessionService.setSecurityDomains(dqpConfiguration.getSecurityDomains());
		} else {
			this.sessionService.setSecurityDomains(Arrays.asList("teiid-security")); //$NON-NLS-1$
		}

		this.sessionService.setVDBRepository(repo);
		this.bufferService.setUseDisk(dqpConfiguration.isUseDisk());
		BufferService bs = getBufferService();
		this.dqp.setBufferManager(bs.getBufferManager());

		startVDBRepository();

		SessionAwareCache<CachedResults> rs = new SessionAwareCache<CachedResults>(dcf, SessionAwareCache.Type.RESULTSET, new CacheConfiguration(Policy.LRU, 60, 250, "resultsetcache")); //$NON-NLS-1$
		SessionAwareCache<PreparedPlan> ppc = new SessionAwareCache<PreparedPlan>(dcf, SessionAwareCache.Type.PREPAREDPLAN,	new CacheConfiguration());
		rs.setTupleBufferCache(bs.getTupleBufferCache());
		this.dqp.setResultsetCache(rs);

		ppc.setTupleBufferCache(bs.getTupleBufferCache());
		this.dqp.setPreparedPlanCache(ppc);

		this.dqp.setTransactionService(this.transactionService);

		this.dqp.start(dqpConfiguration);
		this.sessionService.setDqp(this.dqp);
		this.services.setSecurityHelper(this.sessionService.getSecurityHelper());
		this.logon = new LogonImpl(sessionService, null);
		services.registerClientService(ILogon.class, logon, null);
		services.registerClientService(DQP.class, dqp, null);
		initDriver();
		running = true;
	}

	private void initDriver() {
		driver.setEmbeddedProfile(new ConnectionProfile() {

			@Override
			public ConnectionImpl connect(String url, Properties info)
					throws TeiidSQLException {
				LocalServerConnection conn;
				try {
					conn = new LocalServerConnection(info, useCallingThread) {
						@Override
						protected ClientServiceRegistry getClientServiceRegistry() {
							return services;
						}
					};
				} catch (CommunicationException e) {
					throw TeiidSQLException.create(e);
				} catch (ConnectionException e) {
					throw TeiidSQLException.create(e);
				}
				return new ConnectionImpl(conn, info, url);
			}
		});
	}

	private void startVDBRepository() {
		this.repo.addListener(new VDBLifeCycleListener() {

			@Override
			public void added(String name, int version, CompositeVDB vdb) {

			}

			@Override
			public void removed(String name, int version, CompositeVDB vdb) {
				if (replicator != null) {
					replicator.stop(vdb.getVDB().getAttachment(GlobalTableStore.class));
				}
			}

			@Override
			public void finishedDeployment(String name, int version,
					CompositeVDB vdb) {
				GlobalTableStore gts = new GlobalTableStoreImpl(dqp.getBufferManager(), vdb.getVDB().getAttachment(TransformationMetadata.class));
				if (replicator != null) {
					try {
						gts = replicator.replicate(name + version, GlobalTableStore.class, gts, 300000);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				vdb.getVDB().addAttchment(GlobalTableStore.class, gts);
			}
		});
		this.repo.setSystemFunctionManager(new SystemFunctionManager());
		this.repo.start();
	}

	protected BufferService getBufferService() {
		bufferService.start();
		if (replicator != null) {
			try {
				final TupleBufferCache tbc = replicator.replicate("$BM$", TupleBufferCache.class, bufferService.getBufferManager(), 0); //$NON-NLS-1$
				return new BufferService() {

					@Override
					public BufferManager getBufferManager() {
						return bufferService.getBufferManager();
					}

					@Override
					public TupleBufferCache getTupleBufferCache() {
						return tbc;
					}

				};
			} catch (Exception e) {
				throw new TeiidRuntimeException(e);
			}
		}
		return bufferService;
	}

	/**
	 * Add an {@link ExecutionFactory}.  The {@link ExecutionFactory#start()} method
	 * should have already been called.
	 * @param ef
	 */
	public void addTranslator(ExecutionFactory<?, ?> ef) {
		Translator t = ef.getClass().getAnnotation(Translator.class);
		String name = ef.getClass().getName();
		if (t != null) {
			name = t.name();
		}
		addTranslator(name, ef);
	}
	
	void addTranslator(String name, ExecutionFactory<?, ?> ef) {
		translators.put(name, ef);
	}

	/**
	 * Deploy the given set of models as vdb name.1
	 * @param name
	 * @param models
	 * @throws ConnectorManagerException
	 * @throws VirtualDatabaseException
	 * @throws TranslatorException
	 */
	public void deployVDB(String name, List<ModelMetaData> models)
			throws ConnectorManagerException, VirtualDatabaseException, TranslatorException {
		checkStarted();
		VDBMetaData vdb = new VDBMetaData();
		vdb.setDynamic(true);
		vdb.setName(name);
		vdb.setModels(models);
		cmr.createConnectorManagers(vdb, this);
		MetadataStore metadataStore = new MetadataStore();
		UDFMetaData udfMetaData = new UDFMetaData();
		udfMetaData.setFunctionClassLoader(Thread.currentThread().getContextClassLoader());
		this.assignMetadataRepositories(vdb, null);
		repo.addVDB(vdb, metadataStore, new LinkedHashMap<String, Resource>(), udfMetaData, cmr);
		this.loadMetadata(vdb, cmr, metadataStore);
	}
	
	/**
	 * TODO: consolidate this logic more into the abstract deployer
	 */
	@Override
	protected void loadMetadata(VDBMetaData vdb, ModelMetaData model,
			ConnectorManagerRepository cmr,
			MetadataRepository metadataRepository, MetadataStore store,
			AtomicInteger loadCount) throws TranslatorException {
		Map<String, Datatype> datatypes = this.repo.getBuiltinDatatypes();
		MetadataFactory factory = new MetadataFactory(vdb.getName(), vdb.getVersion(), model.getName(), datatypes, model.getProperties(), model.getSchemaText());
		factory.getSchema().setPhysical(model.isSource());
		
		ExecutionFactory ef = null;
		Object cf = null;
		
		try {
			ConnectorManager cm = getConnectorManager(model, cmr);
			ef = ((cm == null)?null:cm.getExecutionFactory());
			cf = ((cm == null)?null:cm.getConnectionFactory());
		} catch (TranslatorException e1) {
			//ignore data source not availability, it may not be required.
		}
		
		metadataRepository.loadMetadata(factory, ef, cf);		
		metadataLoaded(vdb, model, store, loadCount, factory);
	}
	
	public void undeployVDB(String vdbName) {
		this.repo.removeVDB(vdbName, 1);
	}

	/**
	 * Stops the server.  Once stopped it cannot be restarted.
	 */
	public synchronized void stop() {
		dqp.stop();
		eventDistributorFactoryService.stop();
		bufferService = null;
		dqp = null;
		running = false;
	}

	private synchronized void checkStarted() {
		if (running == null || !running) {
			throw new IllegalStateException();
		}
	}

	public TeiidDriver getDriver() {
		checkStarted();
		return driver;
	}
	
	@Override
	public EventDistributor getEventDistributor() {
		return this.eventDistributorFactoryService.getEventDistributor();
	}

	@SuppressWarnings("unchecked")
	@Override
	public ExecutionFactory<Object, Object> getExecutionFactory(String name)
			throws ConnectorManagerException {
		ExecutionFactory<?, ?> ef = translators.get(name);
		if (ef == null) {
			//TODO: consolidate this exception into the connectormanagerrepository
			throw new ConnectorManagerException(name);
		}
		return (ExecutionFactory<Object, Object>) ef;
	}
	
	@Override
	protected VDBRepository getVDBRepository() {
		return this.repo;
	}
	
}