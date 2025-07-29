package org.tron.core.services.http;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.SignUtils;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.BlockStore;
import org.tron.core.db.common.iterator.DBIterator;
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

@Component
@Slf4j(topic = "API")
public class EnergyPlatformServlet extends RateLimiterServlet {
  
  public static final String OUTPUT_FILE = "energy.txt";
  
  public static final int PROCESSOR_COUNT = 16;

  Queue<Action> queue = new ConcurrentLinkedQueue<>();
  
  Queue<BlockCapsule> blockQueue = new BlockingArrayQueue<>(20000);

  ExecutorService processorPool = Executors.newFixedThreadPool(PROCESSOR_COUNT);
	
	@Data
	@Builder
	public static class Action {
		public String txnId;
		public String date;
		public String signer;
		public String owner;
		public String receiver;
		public String type;
    public String resource;
		public Long amount;
		
		public String toRaw() {
			return txnId + "," + date + "," + signer + "," + owner + "," + receiver + "," + type + "," + resource + "," + amount + "\n";
		}
	}
	
	@Autowired
	private BlockStore blockStore;

	private Set<Protocol.Transaction.Contract.ContractType> types = ImmutableSet.of(
			Protocol.Transaction.Contract.ContractType.FreezeBalanceContract,
			Protocol.Transaction.Contract.ContractType.UnfreezeBalanceContract,
			Protocol.Transaction.Contract.ContractType.FreezeBalanceV2Contract,
			Protocol.Transaction.Contract.ContractType.UnfreezeBalanceV2Contract,
			Protocol.Transaction.Contract.ContractType.DelegateResourceContract,
			Protocol.Transaction.Contract.ContractType.UnDelegateResourceContract);
	
  @Override
  @SneakyThrows
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		long startBlock = Long.parseLong(request.getParameter("start_block"));
		long endBlock = Long.parseLong(request.getParameter("end_block"));
  
    Thread producer = new Thread(() -> {
        try {
          DBIterator it = (DBIterator) blockStore.getDb().iterator();
          it.seek(new BlockCapsule.BlockId(Sha256Hash.ZERO_HASH, startBlock).getBytes());
          
          while (it.hasNext()) {
            BlockCapsule blockCapsule = new BlockCapsule(it.next().getValue());
            if (blockCapsule.getNum() >= endBlock) {
              break;
            }
            blockQueue.offer(blockCapsule);
            if (blockCapsule.getNum() % 1000 == 0) {
              logger.info("Produce block: {}", blockCapsule.getNum());
            }
          }
          it.close();
          
          for (int i = 0; i < PROCESSOR_COUNT; i++) {
            blockQueue.offer(new BlockCapsule(0L, ByteString.EMPTY, 0L, Collections.emptyList()));
          }

          queue.offer(Action.builder().build());
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    });
    
    Runnable processorTask = () -> {
      try {
        while (true) {
          BlockCapsule blockCapsule = blockQueue.poll();
          if (blockCapsule == null) {
            logger.info("Processor empty run");
            Thread.sleep(100);
            continue;
          }
          if (blockCapsule.getNum() == 0L) {
            break;
          }
          if (blockCapsule.getNum() % 1000 == 0) {
            logger.info("Process block: {}", blockCapsule.getNum());
          }
          visit(blockCapsule);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    };
    
    Thread consumer = new Thread(() -> {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            while (true) {
                Action data = queue.poll();
                if (data == null) {
                  logger.info("Consumer empty run");
                  Thread.sleep(100);
                  continue;
                }
                if (data.resource == null) break;
                writer.write(data.toRaw());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    });
    
    producer.start();
    for (int i = 0; i < PROCESSOR_COUNT; i++) {
      processorPool.submit(processorTask);
    }
    consumer.start();

    try {
      response.getWriter().println("ok");
    } catch (Exception e) {
      Util.processError(e, response);
    }
	}
	
	@SneakyThrows
	private void visit(BlockCapsule blockCapsule) {
		long time = blockCapsule.getInstance().getBlockHeader().getRawData().getTimestamp();
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
    formatter.setTimeZone(TimeZone.getTimeZone("GMT+8"));
		String date = formatter.format(new Date(time));
    
    for (Protocol.Transaction txn : blockCapsule.getInstance().getTransactionsList()) {
			TransactionCapsule capsule = new TransactionCapsule(txn);
			Protocol.Transaction.Contract contract = txn.getRawData().getContract(0);
      ByteString signatureHex = txn.getSignature(0);
			Any contractParameter = contract.getParameter();
      
      byte[] hash;
      String base64;
      byte[] address;
      String signer;

			String owner;
      String receiver;
      String resource;
      String txnId;
			long amount;
			
			Action action;
			
      if (types.contains(contract.getType())) {
        txnId = capsule.getTransactionId().toString();

				switch (contract.getType()) {
					case FreezeBalanceContract:
						BalanceContract.FreezeBalanceContract freezeBalanceContract = contractParameter.unpack(
								BalanceContract.FreezeBalanceContract.class);

            owner =  StringUtil.encode58Check(freezeBalanceContract.getOwnerAddress().toByteArray());
            hash = capsule.getTransactionId().getBytes();
            base64 = TransactionCapsule.getBase64FromByteString(signatureHex);
            address = SignUtils.signatureToAddress(hash, base64, CommonParameter.getInstance().isECKeyCryptoEngine());
            signer = StringUtil.encode58Check(address);
            
						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						
            receiver= StringUtil.encode58Check(freezeBalanceContract.getReceiverAddress().toByteArray());
						amount = freezeBalanceContract.getFrozenBalance();
            resource = freezeBalanceContract.getResource().name();

            action = Action.builder().txnId(txnId).date(date).type("freeze")
                .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
						
						queue.offer(action);
						break;
					case FreezeBalanceV2Contract:
						BalanceContract.FreezeBalanceV2Contract freezeBalanceV2Contract =
						    contractParameter.unpack(BalanceContract.FreezeBalanceV2Contract.class);
						owner =  StringUtil.encode58Check(freezeBalanceV2Contract.getOwnerAddress().toByteArray());
            
            hash = capsule.getTransactionId().getBytes();
            base64 = TransactionCapsule.getBase64FromByteString(signatureHex);
            address = SignUtils.signatureToAddress(hash, base64, CommonParameter.getInstance().isECKeyCryptoEngine());
            signer = StringUtil.encode58Check(address);
            
            if (StringUtils.equals(owner, signer)) {
              continue;
            }
            
            receiver = "";
            amount = freezeBalanceV2Contract.getFrozenBalance();
            resource = "";

            action = Action.builder().txnId(txnId).date(date).type("freezev2")
                .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
            
            queue.offer(action);
            break;
          case DelegateResourceContract:
            BalanceContract.DelegateResourceContract delegateResourceContract = contractParameter.unpack(
								BalanceContract.DelegateResourceContract.class);
						
						owner = StringUtil.encode58Check(delegateResourceContract.getOwnerAddress().toByteArray());
            hash = capsule.getTransactionId().getBytes();
            base64 = TransactionCapsule.getBase64FromByteString(signatureHex);
            address = SignUtils.signatureToAddress(hash, base64, CommonParameter.getInstance().isECKeyCryptoEngine());
            signer = StringUtil.encode58Check(address);

						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						
            receiver = StringUtil.encode58Check(delegateResourceContract.getReceiverAddress().toByteArray());
            amount = delegateResourceContract.getBalance();
            resource = delegateResourceContract.getResource().name();
						
						action = Action.builder().txnId(txnId).date(date)
								.type("delegate").owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
						
						queue.offer(action);
            break;
          case UnDelegateResourceContract:
            BalanceContract.UnDelegateResourceContract unDelegateResourceContract = contractParameter
                .unpack(BalanceContract.UnDelegateResourceContract.class);
						
						owner = StringUtil
                .encode58Check(unDelegateResourceContract.getOwnerAddress().toByteArray());
            hash = capsule.getTransactionId().getBytes();
            base64 = TransactionCapsule.getBase64FromByteString(signatureHex);
            address = SignUtils.signatureToAddress(hash, base64, CommonParameter.getInstance().isECKeyCryptoEngine());
            signer = StringUtil.encode58Check(address);

						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						receiver = StringUtil.encode58Check(
								unDelegateResourceContract.getReceiverAddress().toByteArray());
						amount = unDelegateResourceContract.getBalance();
            resource = unDelegateResourceContract.getResource().name();
						
            action =
             Action.builder().txnId(txnId).date(date).type("undelegate")
             .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
            	
						queue.offer(action);
						break;
          case CancelAllUnfreezeV2Contract:
					case UnfreezeBalanceContract:
          case UnfreezeBalanceV2Contract:
          case WithdrawExpireUnfreezeContract:
					default:
						break;
				}
      }
    }
	}
 
 

}
