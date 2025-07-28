package org.tron.core.services.http;

import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import lombok.Builder;
import lombok.Data;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.BlockIndexStore;
import org.tron.core.db.BlockStore;
import org.tron.core.db.TransactionStore;
import org.tron.protos.Protocol;
import org.tron.protos.contract.BalanceContract;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class EnergyPlatformServlet extends RateLimiterServlet {
	
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
			return txnId + "," + date + "," + signer + "," + owner + "," + receiver + "," + type + "," + resource + "," + amount;
		}
	}
	
	@Autowired
	private BlockStore blockStore;
	
	@Autowired
	private BlockIndexStore blockIndexStore;
	
	@Autowired
	private TransactionStore transactionStore;

	private Set<Protocol.Transaction.Contract.ContractType> types = ImmutableSet.of(
			Protocol.Transaction.Contract.ContractType.FreezeBalanceContract,
			Protocol.Transaction.Contract.ContractType.UnfreezeBalanceContract,
			Protocol.Transaction.Contract.ContractType.FreezeBalanceV2Contract,
			Protocol.Transaction.Contract.ContractType.UnfreezeBalanceV2Contract,
			Protocol.Transaction.Contract.ContractType.DelegateResourceContract,
			Protocol.Transaction.Contract.ContractType.UnDelegateResourceContract);
	
  @Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) {
		long startBlock = Long.parseLong(request.getParameter("start_block"));
		long endBlock = Long.parseLong(request.getParameter("end_block"));
		try (FileWriter fileWriter = new FileWriter("energy_" + startBlock + "_" + endBlock + ".txt", true)) {
			List<Action> records = new ArrayList<>();
			
			for (long block = startBlock; block < endBlock; block++) {
				records.addAll(visit(block));
				
				if (block % 1000 == 0) {
					StringBuffer buffer = new StringBuffer();
					records.forEach(it -> buffer.append(it.toRaw()));
					fileWriter.write(buffer.toString());
					records.clear();
				}
			}
			
			response.getWriter().println("ok");
		} catch (Exception e) {
			Util.processError(e, response);
		}
	}
	
	@SneakyThrows
	private List<Action> visit(long blockNumber) {
		List<Action> records = new ArrayList<>();

		BlockCapsule.BlockId id = blockIndexStore.get(blockNumber);
    Protocol.Block block = blockStore.get(id.getBytes()).getInstance();
		
		long time = block.getBlockHeader().getRawData().getTimestamp();
		String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(time));
    
    for (Protocol.Transaction txn : block.getTransactionsList()) {
			TransactionCapsule capsule = new TransactionCapsule(txn);
			Protocol.Transaction.Contract contract = txn.getRawData().getContract(0);
			Any contractParameter = contract.getParameter();
			ByteString signerHex = txn.getRawData().getAuths(0).getAccount().getAddress();
			String signer = StringUtil.encode58Check(signerHex.toByteArray());
			
			String owner;
			String receiver;
      String resource;
			long amount;
			
			Action action;
			
      if (types.contains(contract.getType())) {
				switch (contract.getType()) {
					case FreezeBalanceContract:
						BalanceContract.FreezeBalanceContract freezeBalanceContract = contractParameter.unpack(
								BalanceContract.FreezeBalanceContract.class);

            owner =  StringUtil.encode58Check(freezeBalanceContract.getOwnerAddress().toByteArray());
						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						
            receiver= StringUtil.encode58Check(freezeBalanceContract.getReceiverAddress().toByteArray());
						amount = freezeBalanceContract.getFrozenBalance();
            resource = freezeBalanceContract.getResource().name();

            action = Action.builder().txnId(capsule.getTransactionId().toString()).date(date).type("freeze")
                .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
						
						records.add(action);
						break;
					case FreezeBalanceV2Contract:
						BalanceContract.FreezeBalanceV2Contract freezeBalanceV2Contract =
						    contractParameter.unpack(BalanceContract.FreezeBalanceV2Contract.class);
						owner =  StringUtil.encode58Check(freezeBalanceV2Contract.getOwnerAddress().toByteArray());
            if (StringUtils.equals(owner, signer)) {
              continue;
            }
            
            receiver = "";
            amount = freezeBalanceV2Contract.getFrozenBalance();
            resource = "";

            action = Action.builder().txnId(capsule.getTransactionId().toString()).date(date).type("freezev2")
                .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
            
            records.add(action);
            break;
          case DelegateResourceContract:
            BalanceContract.DelegateResourceContract delegateResourceContract = contractParameter.unpack(
								BalanceContract.DelegateResourceContract.class);
						
						owner = StringUtil.encode58Check(delegateResourceContract.getOwnerAddress().toByteArray());
						
						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						
            receiver = StringUtil.encode58Check(delegateResourceContract.getReceiverAddress().toByteArray());
            amount = delegateResourceContract.getBalance();
            resource = delegateResourceContract.getResource().name();
						
						action = Action.builder().txnId(capsule.getTransactionId().toString()).date(date)
								.type("delegate").owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
						
						records.add(action);
            break;
          case UnDelegateResourceContract:
            BalanceContract.UnDelegateResourceContract unDelegateResourceContract = contractParameter
                .unpack(BalanceContract.UnDelegateResourceContract.class);
						
						owner = StringUtil
                .encode58Check(unDelegateResourceContract.getOwnerAddress().toByteArray());
						if (StringUtils.equals(owner, signer)) {
							continue;
						}
						receiver = StringUtil.encode58Check(
								unDelegateResourceContract.getReceiverAddress().toByteArray());
						amount = unDelegateResourceContract.getBalance();
            resource = unDelegateResourceContract.getResource().name();
						
            action =
             Action.builder().txnId(capsule.getTransactionId().toString()).date(date).type("undelegate")
             .owner(owner).signer(signer).receiver(receiver).resource(resource).amount(amount).build();
            	
						records.add(action);
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
		return records;
	
	}

}
