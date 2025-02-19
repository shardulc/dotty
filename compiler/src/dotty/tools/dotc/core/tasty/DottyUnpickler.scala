package dotty.tools
package dotc
package core
package tasty

import scala.language.unsafeNulls

import Contexts.*, SymDenotations.*,  Decorators.*
import dotty.tools.dotc.ast.tpd
import TastyUnpickler.*
import classfile.ClassfileParser
import Names.SimpleName
import TreeUnpickler.UnpickleMode

import dotty.tools.tasty.TastyReader
import dotty.tools.tasty.TastyFormat.{ASTsSection, PositionsSection, CommentsSection, AttributesSection}
import dotty.tools.tasty.TastyVersion

object DottyUnpickler {

  /** Exception thrown if classfile is corrupted */
  class BadSignature(msg: String) extends RuntimeException(msg)

  class TreeSectionUnpickler(posUnpickler: Option[PositionUnpickler], commentUnpickler: Option[CommentUnpickler], attributeUnpickler: Option[AttributeUnpickler])
  extends SectionUnpickler[TreeUnpickler](ASTsSection) {
    def unpickle(reader: TastyReader, nameAtRef: NameTable): TreeUnpickler =
      new TreeUnpickler(reader, nameAtRef, posUnpickler, commentUnpickler, attributeUnpickler)
  }

  class PositionsSectionUnpickler extends SectionUnpickler[PositionUnpickler](PositionsSection) {
    def unpickle(reader: TastyReader, nameAtRef: NameTable): PositionUnpickler =
      new PositionUnpickler(reader, nameAtRef)
  }

  class CommentsSectionUnpickler extends SectionUnpickler[CommentUnpickler](CommentsSection) {
    def unpickle(reader: TastyReader, nameAtRef: NameTable): CommentUnpickler =
      new CommentUnpickler(reader)
  }

  class AttributesSectionUnpickler extends SectionUnpickler[AttributeUnpickler](AttributesSection) {
    def unpickle(reader: TastyReader, nameAtRef: NameTable): AttributeUnpickler =
      new AttributeUnpickler(reader)
  }
}

/** A class for unpickling Tasty trees and symbols.
 *  @param bytes         the bytearray containing the Tasty file from which we unpickle
 *  @param mode          the tasty file contains package (TopLevel), an expression (Term) or a type (TypeTree)
 */
class DottyUnpickler(bytes: Array[Byte], mode: UnpickleMode = UnpickleMode.TopLevel) extends ClassfileParser.Embedded with tpd.TreeProvider {
  import tpd.*
  import DottyUnpickler.*

  val unpickler: TastyUnpickler = new TastyUnpickler(bytes)
  private val posUnpicklerOpt = unpickler.unpickle(new PositionsSectionUnpickler)
  private val commentUnpicklerOpt = unpickler.unpickle(new CommentsSectionUnpickler)
  private val attributeUnpicklerOpt = unpickler.unpickle(new AttributesSectionUnpickler)
  private val treeUnpickler = unpickler.unpickle(treeSectionUnpickler(posUnpicklerOpt, commentUnpicklerOpt, attributeUnpicklerOpt)).get

  def tastyAttributes: Attributes =
    attributeUnpicklerOpt.map(_.attributes).getOrElse(Attributes.empty)

  /** Enter all toplevel classes and objects into their scopes
   *  @param roots          a set of SymDenotations that should be overwritten by unpickling
   */
  def enter(roots: Set[SymDenotation])(using Context): Unit =
    treeUnpickler.enter(roots)

  protected def treeSectionUnpickler(
    posUnpicklerOpt: Option[PositionUnpickler],
    commentUnpicklerOpt: Option[CommentUnpickler],
    attributeUnpicklerOpt: Option[AttributeUnpickler]
  ): TreeSectionUnpickler =
    new TreeSectionUnpickler(posUnpicklerOpt, commentUnpicklerOpt, attributeUnpicklerOpt)

  protected def computeRootTrees(using Context): List[Tree] = treeUnpickler.unpickle(mode)

  private var ids: Array[String] = null

  override def mightContain(id: String)(using Context): Boolean = {
    if (ids == null)
      ids =
        unpickler.nameAtRef.contents.toArray.collect {
          case name: SimpleName => name.toString
        }.sorted
    ids.binarySearch(id) >= 0
  }
}
