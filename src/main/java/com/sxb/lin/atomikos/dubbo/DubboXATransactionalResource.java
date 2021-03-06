package com.sxb.lin.atomikos.dubbo;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.transaction.xa.XAResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atomikos.datasource.ResourceException;
import com.atomikos.datasource.TransactionalResource;
import com.atomikos.datasource.xa.XATransactionalResource;
import com.atomikos.icatch.config.Configuration;
import com.atomikos.icatch.provider.TransactionServiceProvider;
import com.atomikos.recovery.CoordinatorLogEntry;
import com.atomikos.recovery.LogException;
import com.atomikos.recovery.LogReadException;
import com.atomikos.recovery.ParticipantLogEntry;
import com.atomikos.recovery.RecoveryLog;
import com.atomikos.recovery.TxState;
import com.atomikos.recovery.imp.RecoveryLogImp;
import com.atomikos.recovery.xa.XaResourceRecoveryManager;
import com.sxb.lin.atomikos.dubbo.rocketmq.MQProducerFor2PC;

public class DubboXATransactionalResource extends XATransactionalResource{
	
	private static final Logger LOGGER = LoggerFactory.getLogger(DubboXATransactionalResource.class);

	private Map<String,Long> uniqueResourceNameMap;
	
	private Map<String,Long> recoverMap;
	
	private Set<String> excludeResourceNames;
	
	public DubboXATransactionalResource(Set<String> excludeResourceNames) {
		super("dubboXATransactionalResource");
		this.uniqueResourceNameMap = new ConcurrentHashMap<String, Long>();
		this.recoverMap = new ConcurrentHashMap<String, Long>();
		this.excludeResourceNames = excludeResourceNames;
	}

	@Override
	protected XAResource refreshXAConnection() throws ResourceException {
		return null;
	}
	
	protected XAResource createDubboXAResource(String resourceName) {
		LOGGER.info( this.getName() + ": created " + resourceName + " XAResource" );
		return new DubboXAResourceImpl(resourceName);
	}

	@Override
	public void recover() {
		
		try {
			Set<String> resourceNames = this.getExpiredResourceNames();
			for(String resourceName : recoverMap.keySet()){
				Long timeout = recoverMap.get(resourceName);
				if(timeout != null && timeout.longValue() <= System.currentTimeMillis()){
					recoverMap.remove(resourceName);
				}
				resourceNames.add(resourceName);
			}
			for(String resourceName : uniqueResourceNameMap.keySet()){
				long timeout = uniqueResourceNameMap.remove(resourceName);
				if(timeout > System.currentTimeMillis()){
					Long oldTimeout = recoverMap.get(resourceName);
					if(oldTimeout == null || timeout > oldTimeout.longValue()){
						recoverMap.put(resourceName, timeout);
					}
				}
				resourceNames.add(resourceName);
			}
			if(excludeResourceNames != null && excludeResourceNames.size() > 0){
				resourceNames.removeAll(excludeResourceNames);
			}
			XaResourceRecoveryManager xaResourceRecoveryManager = XaResourceRecoveryManager.getInstance();
			if (xaResourceRecoveryManager != null) {
				for(String resourceName : resourceNames){
					try {
						xaResourceRecoveryManager.recover(this.createDubboXAResource(resourceName));
					} catch (Exception e) {
						LOGGER.error(e.getMessage(), e);
					}
				}
			}
		} catch (LogException couldNotRetrieveCommittingXids) {
			LOGGER.warn("Transient error while recovering - will retry later...", couldNotRetrieveCommittingXids);
		}
		
	}
	
	private Set<String> getExpiredResourceNames() throws LogReadException {
		Set<String> ret = new HashSet<String>();
		Collection<ParticipantLogEntry> entries = this.getUnfinishedParticipants();
		for (ParticipantLogEntry entry : entries) {
			LOGGER.warn("xa command interrupted " + entry.toString());
			if (expired(entry) && !http(entry)) {
				if((!entry.resourceName.equals(entry.coordinatorId + entry.uri)) 
						&& (!entry.resourceName.startsWith(MQProducerFor2PC.MQ_UNIQUE_TOPIC_PREFIX))){
					ret.add(entry.resourceName);
				}
			}
		}
		return ret;
	}
	
	private Collection<ParticipantLogEntry> getUnfinishedParticipants(){
		RecoveryLog log = Configuration.getRecoveryLog();
		RecoveryLogImp impl = (RecoveryLogImp) log;
		CoordinatorLogEntry[] coordinatorLogEntries = impl.getCoordinatorLogEntries();
		Collection<ParticipantLogEntry> allParticipants = new HashSet<ParticipantLogEntry>();
		for (CoordinatorLogEntry coordinatorLogEntry : coordinatorLogEntries) {
			if(coordinatorLogEntry.getResultingState() != TxState.TERMINATED){
				for (ParticipantLogEntry participantLogEntry : coordinatorLogEntry.participants) {
					allParticipants.add(participantLogEntry);
				}
			}
		}
		return allParticipants;
	}

	private boolean http(ParticipantLogEntry entry) {
		return entry.uri.startsWith("http");
	}

	private boolean expired(ParticipantLogEntry entry) {
		long now = System.currentTimeMillis();
		return now > entry.expires;
	}
	
	public TransactionalResource createTransactionalResource(String uniqueResourceName,long timeout){
		if(uniqueResourceName.startsWith(MQProducerFor2PC.MQ_UNIQUE_TOPIC_PREFIX)){
			TransactionalResource transactionalResource = new TemporaryXATransactionalResource(uniqueResourceName){
				@Override
				public void recover() {
					//no recover
				}
			};
			TransactionServiceProvider transactionService = (TransactionServiceProvider) Configuration.getTransactionService();
			transactionalResource.setRecoveryService(transactionService.getRecoveryService());
			return transactionalResource;
		}
		
		recoverMap.remove(uniqueResourceName);
		uniqueResourceNameMap.put(uniqueResourceName, timeout);
		TransactionalResource transactionalResource = new TemporaryXATransactionalResource(uniqueResourceName);
		TransactionServiceProvider transactionService = (TransactionServiceProvider) Configuration.getTransactionService();
		transactionalResource.setRecoveryService(transactionService.getRecoveryService());
		return transactionalResource;
	}
}
