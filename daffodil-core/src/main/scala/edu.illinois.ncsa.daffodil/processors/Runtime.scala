package edu.illinois.ncsa.daffodil.processors

/* Copyright (c) 2012-2013 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 * 
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 * 
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */


import edu.illinois.ncsa.daffodil.api.{ WithDiagnostics, DFDL }
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.dsom.OOLAG.OOLAGException
import edu.illinois.ncsa.daffodil.api.Diagnostic
import edu.illinois.ncsa.daffodil.Implicits._
import edu.illinois.ncsa.daffodil.dsom._
import edu.illinois.ncsa.daffodil.compiler.ProcessorFactory

/**
 * Implementation mixin - provides simple helper methods
 */
trait WithDiagnosticsImpl extends WithDiagnostics {

  //  final lazy val hasDiagnostics = {
  //    getDiagnostics.size > 0
  //  }
}

/**
 * The very last aspects of compilation, and the start of the
 * back-end runtime.
 */
class DataProcessor(pf: ProcessorFactory, val rootElem: GlobalElementDecl)
  extends DFDL.DataProcessor
  with DiagnosticsProviding {
  Assert.usage(pf.canProceed)

  lazy val prettyName = "DataProcessor"
  lazy val path = prettyName

  lazy val diagnosticChildren: DiagnosticsList = {
    parser
    // force creation of the parser value so that all errors are issued
    // this is in case some compilation happens in the constructors of parsers.
    val res = List(rootElem, { parser; parser_ })
    res
  }

  //
  // Last tidbits of compilation. Really this is just accessing the 
  // result of compilation
  // 
  lazy val parser = parser_.value
  private lazy val parser_ = LV('parser) {
    rootElem.document.parser
  }

  lazy val unparser = unparser_.value
  private lazy val unparser_ = LV('unparser) {
    rootElem.document.unparser
  }

  def save(output: DFDL.Output): Unit = {
    Assert.notYetImplemented()
  }

  /**
   * Here begins the parser runtime. Compiler-oriented mechanisms (OOLAG etc.) aren't used in the
   * runtime. Instead we deal with success and failure statuses.
   */
  def parse(input: DFDL.Input, lengthLimitInBits: Long = -1): DFDL.ParseResult = {
    Assert.usage(!this.isError)

    val initialState = PState.createInitialState(
      rootElem,
      input,
      bitOffset = 0,
      bitLengthLimit = lengthLimitInBits) // TODO also want to pass here the externally set variables, other flags/settings.
    parse(initialState)
  }

  def parse(initialState: PState) = {
    val pr = new ParseResult(this) {

      val resultState = { // Not lazy. We want to parse right now.
        try {
          parser.parse1(initialState, rootElem)
        } catch {
          // technically, runtime shouldn't throw. It's really too heavyweight a construct. And "failure" 
          // when parsing isn't exceptional, it's routine behavior. So ought not be implemented via an 
          // exception handling construct.
          //
          // But we might not catch everything inside...
          //
          case pe: ParseError => {
            // if we get one here, then someone threw instead of returning a status. 
            Assert.invariantFailed("ParseError caught. ParseErrors should be returned as failed status, not thrown. Fix please.")
          }
          case procErr: ProcessingError => {
            Assert.invariantFailed("got a processing error that was not a parse error. This is the parser!")
          }
          case sde: SchemaDefinitionError => {
            // A SDE was detected at runtime (perhaps due to a runtime-valued property like byteOrder or encoding)
            // These are fatal, and there's no notion of backtracking them, so they propagate to top level
            // here.
            initialState.failed(sde)
          }
          case e: OOLAGException => {
            Assert.invariantFailed("OOLAGException's like " + e + " are compiler stuff. This is runtime.")
          }
        }
      }
    }
    pr
  }

  /**
   * Unparser runtime begins here. Compiler-oriented mechanisms (OOLAG etc.) aren't used in the
   * runtime. Instead we deal with success and failure statuses.
   */
  def unparse(output: DFDL.Output, infoset: scala.xml.Node): DFDL.UnparseResult = {
    Assert.usage(!this.isError)

    val jdomElem = XMLUtils.elem2Element(infoset)
    val jdomDoc = new org.jdom.Document(jdomElem)
    val initialState = UState.createInitialState(rootElem, output, jdomDoc) // also want to pass here the externally set variables, other flags/settings.

    val uRes = new UnparseResult(this) {
      val resultState = { // Not lazy. We want to unparse right now.

        try {
          unparser.unparse(initialState)
        } catch {
          // technically, runtime shouldn't throw. It's really too heavyweight a construct. And "failure" 
          // when parsing isn't exceptional, it's routine behavior. So ought not be implemented via an 
          // exception handling construct.
          //
          // But we might not catch everything inside...
          //
          case pe: UnparseError => {
            // if we get one here, then someone threw instead of returning a status. 
            Assert.invariantFailed("UnparseError caught. UnparseErrors should be returned as failed status, not thrown. Fix please.")
          }
          case procErr: ProcessingError => {
            Assert.invariantFailed("got a processing error that was not an unparse error. This is the unparser!")
          }
          case sde: SchemaDefinitionError => {
            // A SDE was detected at runtime (perhaps due to a runtime-valued property like byteOrder or encoding)
            // These are fatal, and there's no notion of backtracking them, so they propagate to top level here.
            initialState.failed(sde)
          }
          case e: OOLAGException => {
            Assert.invariantFailed("OOLAGExceptions like " + e.toString() + " are compiler stuff. This is runtime.")
          }
        }
      }
      //write unparsed result to outputStream
      resultState.outStream.write()
    }
    uRes
  }
}

abstract class ParseResult(dp: DataProcessor)
  extends DFDL.ParseResult
  with WithDiagnosticsImpl {

  def resultState: PState

  lazy val result =
    if (resultState.status == Success) {
      val xmlNode = resultState.infoset.toXML
      xmlNode
    } else {
      <nothing/>
    }
}

abstract class UnparseResult(dp: DataProcessor)
  extends DFDL.UnparseResult
  with WithDiagnosticsImpl {

  override def resultState: UState
}

