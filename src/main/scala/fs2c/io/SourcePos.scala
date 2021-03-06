package fs2c.io

import scala.io.AnsiColor._

case class SourcePos(source: ScalaSource, line: Int, col: Int, idx: Int) {
  def prevContext(n: Int): String = {
    val start = math.max(0, line - n)
    val lines = (start until line) map { i => s" ${i + 1} | ${source.lines(i)}\n" }
    lines mkString ""
  }

  def nextContext(n: Int): String = {
    val end = math.min(source.lineCount - 1, line + n)
    val lines = ((line + 1) to end) map { i => s" ${i + 1} | ${source.lines(i)}\n" }
    lines mkString ""
  }

  def lineStr: String = source.lines(line)

  def < (other: SourcePos): Boolean = idx < other.idx
}

case class SourcePosSpan(start: SourcePos, length: Int) {
  def -- (other: SourcePosSpan): SourcePosSpan = {
    assert(start.source == other.start.source, "Source position should be spanned over the same source.")
    assert(other.start.idx + other.length - start.idx >= 0, "Source position should be spanned from before to after.")
    SourcePosSpan(start, other.start.idx + other.length - start.idx)
  }

  def showWithContext(n: Int, hint: String = ""): String = {
    val prevContext = start.prevContext(n)
    val nextContext = start.nextContext(n)
    val thisLine = showInSourceLine(hint)

    prevContext ++ thisLine ++ "\n" ++ nextContext
  }

  def showInSourceLine(hint: String = ""): String = {
    val lineNum = start.line
    val linePos = start.col
    val lineStr = start.lineStr

    val header = s" ${lineNum + 1} | "
    val signSpace = " ".repeat(header.length + linePos)
    val sign = "^".repeat(if length <= 0 then 1 else length)
    s"$RESET$BOLD$header$lineStr$RESET\n$BOLD$RED$signSpace$sign$RESET\n$signSpace$BOLD$RED$hint$RESET"
  }

  def < (other: SourcePosSpan): Boolean = start < other.start

  def || (other: SourcePosSpan): SourcePosSpan =
    if start < other.start then other else this
}

trait Positional {
  type PosSelf >: this.type <: Positional

  private var myPos: SourcePosSpan = null

  def pos: SourcePosSpan = myPos

  def withPos(newPos: => SourcePosSpan): PosSelf = {
    myPos = newPos
    this
  }

  def withPos(other: Positional): PosSelf =
//    assert(other.myPos ne null, "inheriting position from an unpositioned Positional element: other")
    withPos(other.myPos)

  /** Show the positional element in the line.
    *
    * @return
    */
  def showInSourceLine(hint: String = ""): String = myPos match {
    case null => s"<no position>\n$hint"
    case pos => pos.showInSourceLine(hint)
  }

  def showWithContext(n: Int, hint: String = ""): String = myPos match {
    case null => s"<no position>\n$hint"
    case pos => pos.showWithContext(n, hint)
  }

  def -- (other: Positional): SourcePosSpan = {
    assert(myPos ne null, s"spanning from a Positional element with null position: $this")
    assert(other.myPos ne null, s"spanning to a Positional element with null position: $other")

    myPos -- other.myPos
  }

  def || (other: PosSelf): PosSelf =
    (myPos, other.myPos) match {
      case (null, _) => other
      case (_, null) => this
      case (p1, p2) if p1 < p2 => other
      case _ => this
    }
}
