package org.tron.consensus.dpos;

import static org.tron.common.utils.WalletUtil.getAddressStringList;
import static org.tron.core.Constant.ONE_YEAR_MS;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Pair;
import org.tron.consensus.ConsensusDelegate;
import org.tron.consensus.pbft.PbftManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.db.CommonDataBase;
import org.tron.core.db.CrossRevokingStore;
import org.tron.core.store.DelegationStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.store.VotesStore;
import org.tron.protos.Protocol.PBFTCommitResult;
import org.tron.protos.Protocol.PBFTMessage;
import org.tron.protos.Protocol.PBFTMessage.Raw;
import org.tron.protos.contract.BalanceContract.CrossChainInfo;
import org.tron.protos.contract.CrossChain;

@Slf4j(topic = "consensus")
@Component
public class MaintenanceManager {

  @Autowired
  private ConsensusDelegate consensusDelegate;

  @Autowired
  private IncentiveManager incentiveManager;

  @Setter
  private DposService dposService;

  @Setter
  private PbftManager pbftManager;

  @Getter
  private final List<ByteString> beforeWitness = new ArrayList<>();
  @Getter
  private final List<ByteString> currentWitness = new ArrayList<>();
  @Getter
  private long beforeMaintenanceTime;

  public void init() {
    currentWitness.addAll(consensusDelegate.getActiveWitnesses());
  }

  public void applyBlock(BlockCapsule blockCapsule) {
    long blockNum = blockCapsule.getNum();
    long blockTime = blockCapsule.getTimeStamp();
    long nextMaintenanceTime = consensusDelegate.getNextMaintenanceTime();
    boolean flag = consensusDelegate.getNextMaintenanceTime() <= blockTime;
    if (flag) {
      if (blockNum != 1) {
        updateWitnessValue(beforeWitness);
        beforeMaintenanceTime = nextMaintenanceTime;
        doMaintenance();
        updateWitnessValue(currentWitness);
      }
      consensusDelegate.updateNextMaintenanceTime(blockTime);
      if (blockNum != 1) {
        //pbft sr msg
        pbftManager.srPrePrepare(blockCapsule, currentWitness,
            consensusDelegate.getNextMaintenanceTime());
      }
    }
    consensusDelegate.saveStateFlag(flag ? 1 : 0);
    //pbft block msg
    if (blockNum == 1) {
      nextMaintenanceTime = consensusDelegate.getNextMaintenanceTime();
    }
    pbftManager.blockPrePrepare(blockCapsule, nextMaintenanceTime);
  }

  private void updateWitnessValue(List<ByteString> srList) {
    srList.clear();
    srList.addAll(consensusDelegate.getActiveWitnesses());
  }

  public void doMaintenance() {
    VotesStore votesStore = consensusDelegate.getVotesStore();

    tryRemoveThePowerOfTheGr();

    Map<ByteString, Long> countWitness = countVote(votesStore);
    if (!countWitness.isEmpty()) {
      List<ByteString> currentWits = consensusDelegate.getActiveWitnesses();

      List<ByteString> newWitnessAddressList = new ArrayList<>();
      consensusDelegate.getAllWitnesses()
          .forEach(witnessCapsule -> newWitnessAddressList.add(witnessCapsule.getAddress()));

      countWitness.forEach((address, voteCount) -> {
        byte[] witnessAddress = address.toByteArray();
        WitnessCapsule witnessCapsule = consensusDelegate.getWitness(witnessAddress);
        if (witnessCapsule == null) {
          logger.warn("Witness capsule is null. address is {}", Hex.toHexString(witnessAddress));
          return;
        }
        AccountCapsule account = consensusDelegate.getAccount(witnessAddress);
        if (account == null) {
          logger.warn("Witness account is null. address is {}", Hex.toHexString(witnessAddress));
          return;
        }
        witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() + voteCount);
        consensusDelegate.saveWitness(witnessCapsule);
        logger.info("address is {} , countVote is {}", witnessCapsule.createReadableString(),
            witnessCapsule.getVoteCount());
      });

      dposService.updateWitness(newWitnessAddressList);

      incentiveManager.reward(newWitnessAddressList);

      List<ByteString> newWits = consensusDelegate.getActiveWitnesses();
      if (!CollectionUtils.isEqualCollection(currentWits, newWits)) {
        currentWits.forEach(address -> {
          WitnessCapsule witnessCapsule = consensusDelegate.getWitness(address.toByteArray());
          witnessCapsule.setIsJobs(false);
          consensusDelegate.saveWitness(witnessCapsule);
        });
        newWits.forEach(address -> {
          WitnessCapsule witnessCapsule = consensusDelegate.getWitness(address.toByteArray());
          witnessCapsule.setIsJobs(true);
          consensusDelegate.saveWitness(witnessCapsule);
        });
      }

      logger.info("Update witness success. \nbefore: {} \nafter: {}",
          getAddressStringList(currentWits),
          getAddressStringList(newWits));
    }

    DynamicPropertiesStore dynamicPropertiesStore = consensusDelegate.getDynamicPropertiesStore();
    DelegationStore delegationStore = consensusDelegate.getDelegationStore();
    if (dynamicPropertiesStore.allowChangeDelegation()) {
      long nextCycle = dynamicPropertiesStore.getCurrentCycleNumber() + 1;
      dynamicPropertiesStore.saveCurrentCycleNumber(nextCycle);
      consensusDelegate.getAllWitnesses().forEach(witness -> {
        delegationStore.setBrokerage(nextCycle, witness.createDbKey(),
            delegationStore.getBrokerage(witness.createDbKey()));
        delegationStore.setWitnessVote(nextCycle, witness.createDbKey(), witness.getVoteCount());
      });
    }

    // update parachains
    long currentBlockHeaderTimestamp = dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
    // todo get auction
    List<CrossChain.AuctionRoundContract> auctionRoundContractList = new LinkedList<>();
    auctionRoundContractList.forEach(roundInfo -> {
      if (roundInfo.getEndTime() < currentBlockHeaderTimestamp) {
        CrossRevokingStore crossRevokingStore = consensusDelegate.getCrossRevokingStore();
        if (currentBlockHeaderTimestamp < roundInfo.getEndTime() + roundInfo.getDuration()) {
          if (crossRevokingStore.getParaChainList(roundInfo.getRound()).isEmpty()) {
            // set parachains
            List<Pair<String, Long>> eligibleChainLists =
                    crossRevokingStore.getEligibleChainLists(roundInfo.getRound(), roundInfo.getSlotCount());
            List<String> chainIds = eligibleChainLists.stream().map(Pair::getKey)
                    .collect(Collectors.toList());
            crossRevokingStore.updateParaChains(roundInfo.getRound(), chainIds);

            setChainInfo(chainIds);
          }
        } else {
          crossRevokingStore.deleteParaChains(roundInfo.getRound());
        }
      }
    });

  }

  private void setChainInfo(List<String> chainIds) {
    CrossRevokingStore crossRevokingStore = consensusDelegate.getCrossRevokingStore();
    CommonDataBase commonDataBase = consensusDelegate.getChainBaseManager().getCommonDataBase();
    chainIds.forEach(chainId -> {
      try {
        byte[] chainInfoData = crossRevokingStore.getChainInfo(chainId);
        if (ByteArray.isEmpty(chainInfoData)) {
          return;
        }
        CrossChainInfo crossChainInfo = CrossChainInfo.parseFrom(chainInfoData);
        if (crossChainInfo.getBeginSyncHeight() - 1 <= commonDataBase
            .getLatestHeaderBlockNum(chainId)) {
          return;
        }
        commonDataBase.saveLatestHeaderBlockNum(chainId, crossChainInfo.getBeginSyncHeight() - 1);
        commonDataBase.saveLatestBlockHeaderHash(chainId,
            ByteArray.toHexString(crossChainInfo.getParentBlockHash().toByteArray()));
        long round = crossChainInfo.getBlockTime() / crossChainInfo.getMaintenanceTimeInterval();
        long epoch = (round + 1) * crossChainInfo.getMaintenanceTimeInterval();
        if (crossChainInfo.getBlockTime() % crossChainInfo.getMaintenanceTimeInterval() == 0) {
          epoch = epoch - crossChainInfo.getMaintenanceTimeInterval();
          epoch = epoch < 0 ? 0 : epoch;
        }
        PBFTMessage.Raw pbftMsgRaw = Raw.newBuilder().setData(crossChainInfo.getSrList())
            .setEpoch(epoch).build();
        PBFTCommitResult.Builder builder = PBFTCommitResult.newBuilder();
        builder.setData(pbftMsgRaw.toByteString());
        commonDataBase.saveSRL(chainId, epoch, builder.build());
      } catch (InvalidProtocolBufferException e) {
        logger.error("chain {} get the info fail!", chainId, e);
      }
    });
  }

  private Map<ByteString, Long> countVote(VotesStore votesStore) {
    final Map<ByteString, Long> countWitness = Maps.newHashMap();
    Iterator<Entry<byte[], VotesCapsule>> dbIterator = votesStore.iterator();
    long sizeCount = 0;
    while (dbIterator.hasNext()) {
      Entry<byte[], VotesCapsule> next = dbIterator.next();
      VotesCapsule votes = next.getValue();
      votes.getOldVotes().forEach(vote -> {
        ByteString voteAddress = vote.getVoteAddress();
        long voteCount = vote.getVoteCount();
        if (countWitness.containsKey(voteAddress)) {
          countWitness.put(voteAddress, countWitness.get(voteAddress) - voteCount);
        } else {
          countWitness.put(voteAddress, -voteCount);
        }
      });
      votes.getNewVotes().forEach(vote -> {
        ByteString voteAddress = vote.getVoteAddress();
        long voteCount = vote.getVoteCount();
        if (countWitness.containsKey(voteAddress)) {
          countWitness.put(voteAddress, countWitness.get(voteAddress) + voteCount);
        } else {
          countWitness.put(voteAddress, voteCount);
        }
      });
      sizeCount++;
      votesStore.delete(next.getKey());
    }
    logger.info("There is {} new votes in this epoch", sizeCount);
    return countWitness;
  }

  private void tryRemoveThePowerOfTheGr() {
    if (consensusDelegate.getRemoveThePowerOfTheGr() != 1) {
      return;
    }
    dposService.getGenesisBlock().getWitnesses().forEach(witness -> {
      WitnessCapsule witnessCapsule = consensusDelegate.getWitness(witness.getAddress());
      witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() - witness.getVoteCount());
      consensusDelegate.saveWitness(witnessCapsule);
    });
    consensusDelegate.saveRemoveThePowerOfTheGr(-1);
  }

}
