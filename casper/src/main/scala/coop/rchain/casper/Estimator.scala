package coop.rchain.casper

import scala.collection.immutable.{Map, Set}

import cats.Monad
import cats.implicits._

import coop.rchain.blockstorage.BlockDagRepresentation
import coop.rchain.casper.protocol.BlockMessage
import coop.rchain.casper.util.DagOperations
import coop.rchain.casper.util.ProtoUtil.weightFromValidatorByDag
import coop.rchain.catscontrib.ListContrib
import coop.rchain.metrics.Metrics
import coop.rchain.models.BlockMetadata

import com.google.protobuf.ByteString

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

  implicit val decreasingOrder = Ordering[Long].reverse

  private val EstimatorMetricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "estimator")
  private val Tips0MetricsSource: Metrics.Source = Metrics.Source(EstimatorMetricsSource, "tips0")
  private val Tips1MetricsSource: Metrics.Source = Metrics.Source(EstimatorMetricsSource, "tips1")

  def tips[F[_]: Monad: Metrics](
      blockDag: BlockDagRepresentation[F],
      genesis: BlockMessage
  ): F[IndexedSeq[BlockHash]] =
    for {
      span                <- Metrics[F].span(Tips0MetricsSource)
      latestMessageHashes <- blockDag.latestMessageHashes
      _                   <- span.mark("latest-message-hashes")
      result              <- Estimator.tips[F](blockDag, genesis, latestMessageHashes)
      _                   <- span.close()
    } yield result

  /**
    * When the BlockDag has an empty latestMessages, tips will return IndexedSeq(genesis.blockHash)
    */
  def tips[F[_]: Monad: Metrics](
      blockDag: BlockDagRepresentation[F],
      genesis: BlockMessage,
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[IndexedSeq[BlockHash]] =
    for {
      span                       <- Metrics[F].span(Tips1MetricsSource)
      lca                        <- calculateLCA(blockDag, BlockMetadata.fromBlock(genesis, false), latestMessagesHashes)
      _                          <- span.mark("lca")
      scoresMap                  <- buildScoresMap(blockDag, latestMessagesHashes, lca)
      _                          <- span.mark("score-map")
      rankedLatestMessagesHashes <- rankForkchoices(List(lca), blockDag, scoresMap)
      _                          <- span.mark("ranked-latest-messages-hashes")
      _                          <- span.close()
    } yield rankedLatestMessagesHashes

  private def calculateLCA[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      genesis: BlockMetadata,
      latestMessagesHashes: Map[Validator, BlockHash]
  ): F[BlockHash] =
    for {
      latestMessages <- latestMessagesHashes.values.toStream
                         .traverse(hash => blockDag.lookup(hash))
                         .map(_.flatten)
      result <- if (latestMessages.isEmpty) {
                 genesis.blockHash.pure[F]
               } else {
                 latestMessages
                   .foldM(latestMessages.head) {
                     case (acc, latestMessage) =>
                       // TODO: Change to mainParentLCA
                       DagOperations.lowestCommonAncestorF[F](
                         acc,
                         latestMessage,
                         blockDag
                       )
                   }
                   .map(_.blockHash)
               }
    } yield result

  private def buildScoresMap[F[_]: Monad](
      blockDag: BlockDagRepresentation[F],
      latestMessagesHashes: Map[Validator, BlockHash],
      lowestCommonAncestor: BlockHash
  ): F[Map[BlockHash, Long]] = {
    def hashParents(hash: BlockHash, lastFinalizedBlockNumber: Long): F[List[BlockHash]] =
      for {
        currentBlockNumber <- blockDag.lookup(hash).map(_.get.blockNum)
        result <- if (currentBlockNumber < lastFinalizedBlockNumber)
                   List.empty[BlockHash].pure[F]
                 else
                   blockDag.lookup(hash).map(_.get.parents)
      } yield result

    def addValidatorWeightDownSupportingChain(
        scoreMap: Map[BlockHash, Long],
        validator: Validator,
        latestBlockHash: BlockHash
    ): F[Map[BlockHash, Long]] =
      for {
        lcaBlockNum <- blockDag.lookup(lowestCommonAncestor).map(_.get.blockNum)
        result <- DagOperations
                   .bfTraverseF[F, BlockHash](List(latestBlockHash))(
                     hashParents(_, lcaBlockNum)
                   )
                   .foldLeftF(scoreMap) {
                     case (acc, hash) =>
                       val currScore = acc.getOrElse(hash, 0L)
                       weightFromValidatorByDag(blockDag, hash, validator).map { validatorWeight =>
                         acc.updated(hash, currScore + validatorWeight)
                       }
                   }
      } yield result

    // TODO: Since map scores are additive it should be possible to do this in parallel
    latestMessagesHashes.toList.foldLeftM(Map.empty[BlockHash, Long]) {
      case (acc, (validator: Validator, latestBlockHash: BlockHash)) =>
        addValidatorWeightDownSupportingChain(
          acc,
          validator,
          latestBlockHash
        )
    }
  }

  private def rankForkchoices[F[_]: Monad](
      blocks: List[BlockHash],
      blockDag: BlockDagRepresentation[F],
      scores: Map[BlockHash, Long]
  ): F[IndexedSeq[BlockHash]] =
    // TODO: This ListContrib.sortBy will be improved on Thursday with Pawels help
    for {
      unsortedNewBlocks <- blocks.flatTraverse(replaceBlockHashWithChildren[F](_, blockDag, scores))
      newBlocks = ListContrib.sortBy[BlockHash, Long](
        unsortedNewBlocks.distinct,
        scores
      )
      result <- if (stillSame(blocks, newBlocks)) {
                 blocks.toVector.pure[F]
               } else {
                 rankForkchoices(newBlocks, blockDag, scores)
               }
    } yield result

  private def toNonEmptyList[T](elements: Set[T]): Option[List[T]] =
    if (elements.isEmpty) None
    else Some(elements.toList)

  /**
    * Only include children that have been scored,
    * this ensures that the search does not go beyond
    * the messages defined by blockDag.latestMessages
    */
  private def replaceBlockHashWithChildren[F[_]: Monad](
      b: BlockHash,
      blockDag: BlockDagRepresentation[F],
      scores: Map[BlockHash, Long]
  ): F[List[BlockHash]] =
    blockDag
      .children(b)
      .map(
        maybeChildren =>
          maybeChildren
            .flatMap { children =>
              toNonEmptyList(children.filter(scores.contains))
            }
            .getOrElse(List(b))
      )

  private def stillSame(blocks: List[BlockHash], newBlocks: List[BlockHash]): Boolean =
    newBlocks == blocks
}
