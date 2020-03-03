package org.tron.core.consensus;

import static org.tron.common.utils.ByteArray.fromHexString;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.crypto.SignUtils;
import org.tron.consensus.Consensus;
import org.tron.consensus.base.Param;
import org.tron.consensus.base.Param.Miner;
import org.tron.core.config.args.Args;
import org.tron.core.store.WitnessStore;

@Slf4j(topic = "consensus")
@Component
public class ConsensusService {

  @Autowired
  private Consensus consensus;

  @Autowired
  private WitnessStore witnessStore;

  @Autowired
  private BlockHandleImpl blockHandle;

  private Args args = Args.getInstance();

  public void start() {
    Param param = new Param();
    param.setEnable(args.isWitness());
    param.setGenesisBlock(args.getGenesisBlock());
    param.setMinParticipationRate(args.getMinParticipationRate());
    param.setBlockProduceTimeoutPercent(Args.getInstance().getBlockProducedTimeOut());
    param.setNeedSyncCheck(args.isNeedSyncCheck());
    List<Miner> miners = new ArrayList<>();

    List<String> privateKeys = Args.getInstance().getLocalWitnesses().getPrivateKeys();
    for (String key : privateKeys) {
      byte[] privateKey = fromHexString(key);
      byte[] privateKeyAddress = SignUtils
          .fromPrivate(privateKey, Args.getInstance().isECKeyCryptoEngine()).getAddress();
      Miner miner = param.new Miner(privateKey, ByteString.copyFrom(privateKeyAddress),
          ByteString.copyFrom(privateKeyAddress));
      miners.add(miner);
      logger.info("Add witness: {}, size: {}",
          Hex.toHexString(privateKeyAddress), miners.size());
    }

//    byte[] privateKey =
//        fromHexString(Args.getInstance().getLocalWitnesses().getPrivateKey());
//    byte[] privateKeyAddress = SignUtils.fromPrivate(privateKey,
//        Args.getInstance().isECKeyCryptoEngine()).getAddress();
//    byte[] witnessAddress = Args.getInstance().getLocalWitnesses().getWitnessAccountAddress(
//        DBConfig.isECKeyCryptoEngine());
//    WitnessCapsule witnessCapsule = witnessStore.get(witnessAddress);
//    if (null == witnessCapsule) {
//      logger.warn("Witness {} is not in witnessStore.", Hex.encodeHexString(witnessAddress));
//    } else {
//      Miner miner = param.new Miner(privateKey, ByteString.copyFrom(privateKeyAddress),
//          ByteString.copyFrom(witnessAddress));
//      miners.add(miner);
//    }
    param.setMiners(miners);
    param.setBlockHandle(blockHandle);
    consensus.start(param);
  }

  public void stop() {
    consensus.stop();
  }

}
