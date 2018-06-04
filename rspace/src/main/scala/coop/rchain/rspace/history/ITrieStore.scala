package coop.rchain.rspace.history

import cats.instances.option._
import cats.instances.vector._
import cats.syntax.traverse._

import scala.annotation.tailrec
import scala.collection.immutable.Seq

trait ITrieStore[T, K, V] {

  private[rspace] def createTxnRead(): T

  private[rspace] def createTxnWrite(): T

  private[rspace] def withTxn[R](txn: T)(f: T => R): R

  private[rspace] def getRoot(txn: T): Option[Blake2b256Hash]

  private[rspace] def putRoot(txn: T, hash: Blake2b256Hash): Unit

  private[rspace] def put(txn: T, key: Blake2b256Hash, value: Trie[K, V]): Unit

  private[rspace] def get(txn: T, key: Blake2b256Hash): Option[Trie[K, V]]

  private[rspace] def toMap: Map[Blake2b256Hash, Trie[K, V]]

  private[rspace] def getLeaves(txn: T, hash: Blake2b256Hash): Seq[Leaf[K, V]] = {
    @tailrec
    def loop(txn: T, ts: Seq[Trie[K, V]], ls: Seq[Leaf[K, V]]): Seq[Leaf[K, V]] =
      ts match {
        case Seq() =>
          ls
        case tries =>
          val (next, acc) = tries.foldLeft((Seq.empty[Trie[K, V]], ls)) {
            case ((nexts, leaves), Node(pointerBlock)) =>
              val children =
                pointerBlock.children
                  .map(_._2)
                  .traverse[Option, Trie[K, V]](hash => get(txn, hash))
                  .getOrElse(throw new LookupException("something went wrong"))
              (nexts ++ children, leaves)
            case ((nexts, leaves), leaf: Leaf[K, V]) =>
              (nexts, leaves :+ leaf)
          }
          loop(txn, next, acc)
      }
    get(txn, hash) match {
      case Some(currentRoot) =>
        loop(txn, Seq(currentRoot), Seq.empty[Leaf[K, V]])
      case None =>
        throw new LookupException(s"could not get node at $hash ")
    }
  }

  private[rspace] def clear(txn: T): Unit

  def close(): Unit
}