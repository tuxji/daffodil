package edu.illinois.ncsa.daffodil.processors

import edu.illinois.ncsa.daffodil.api.WithDiagnostics
import edu.illinois.ncsa.daffodil.xml.XMLUtils
import edu.illinois.ncsa.daffodil.xml.JDOMUtils
import edu.illinois.ncsa.daffodil.xml.NS
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.dsom.oolag.OOLAG.OOLAGException
import edu.illinois.ncsa.daffodil.Implicits._
import edu.illinois.ncsa.daffodil.dsom._
import edu.illinois.ncsa.daffodil.dsom.DiagnosticUtils._
import edu.illinois.ncsa.daffodil.dsom.oolag.OOLAG.ErrorAlreadyHandled
import edu.illinois.ncsa.daffodil.dsom.oolag.OOLAG.HasIsError
import edu.illinois.ncsa.daffodil.ExecutionMode
import edu.illinois.ncsa.daffodil.api.DFDL
import edu.illinois.ncsa.daffodil.api.WithDiagnostics
import org.jdom2.Namespace
import edu.illinois.ncsa.daffodil.api.DFDL
import edu.illinois.ncsa.daffodil.dsom.ValidationError
import edu.illinois.ncsa.daffodil.util.Validator
import org.xml.sax.SAXParseException
import org.xml.sax.SAXException
import edu.illinois.ncsa.daffodil.util.ValidationException
import edu.illinois.ncsa.daffodil.api.ValidationMode
import edu.illinois.ncsa.daffodil.externalvars.ExternalVariablesLoader
import scala.xml.Node
import java.io.File
import edu.illinois.ncsa.daffodil.externalvars.Binding
import edu.illinois.ncsa.daffodil.processors.charset.CharsetUtils
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import edu.illinois.ncsa.daffodil.schema.annotation.props.gen.EncodingErrorPolicy
import java.nio.channels.FileChannel
import edu.illinois.ncsa.daffodil.util.Maybe
import edu.illinois.ncsa.daffodil.util.Maybe._
import java.io.ObjectOutputStream
import edu.illinois.ncsa.daffodil.util.Logging
import edu.illinois.ncsa.daffodil.util.PreSerialization
import edu.illinois.ncsa.daffodil.compiler.DaffodilTunableParameters.TunableLimitExceededError
import edu.illinois.ncsa.daffodil.debugger.Debugger
import java.util.zip.GZIPOutputStream

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
class DataProcessor(val ssrd: SchemaSetRuntimeData)
  extends DFDL.DataProcessor with HasIsError with Logging with Serializable {

  def setValidationMode(mode: ValidationMode.Type): Unit = { ssrd.validationMode = mode }
  def getValidationMode() = ssrd.validationMode
  def getVariables = ssrd.variables

  def setExternalVariables(extVars: Map[String, String]): Unit = {
    val bindings = ExternalVariablesLoader.getVariables(extVars)
    ExternalVariablesLoader.loadVariables(bindings, ssrd, ssrd.variables)
    ssrd.variables = ExternalVariablesLoader.loadVariables(extVars, ssrd, ssrd.variables)
  }
  def setExternalVariables(extVars: File): Unit = {
    ssrd.variables = ExternalVariablesLoader.loadVariables(extVars, ssrd, ssrd.variables)
  }
  def setExternalVariables(extVars: Seq[Binding]): Unit = {
    ssrd.variables = ExternalVariablesLoader.loadVariables(extVars, ssrd, ssrd.variables)
  }

  override def isError = false // really there is no compiling at all currently, so there can be no errors.

  override def getDiagnostics = ssrd.diagnostics

  def save(output: DFDL.Output): Unit = {
    val oos = new ObjectOutputStream(new GZIPOutputStream(Channels.newOutputStream(output)))
    oos.writeObject(this)
    oos.close()
  }

  /**
   * Here begins the parser runtime. Compiler-oriented mechanisms (OOLAG etc.) aren't used in the
   * runtime. Instead we deal with success and failure statuses.
   */
  def parse(input: DFDL.Input, lengthLimitInBits: Long = -1): DFDL.ParseResult = {
    Assert.usage(!this.isError)

    val rootERD = ssrd.elementRuntimeData

    val initialState =
      if (ssrd.encodingInfo.isScannable &&
        ssrd.encodingInfo.defaultEncodingErrorPolicy == EncodingErrorPolicy.Replace &&
        ssrd.knownEncodingIsFixedWidth &&
        ssrd.knownEncodingAlignmentInBits == 8 // byte-aligned characters
        ) {
        // use simpler text only I/O layer
        val jis = Channels.newInputStream(input)
        val inStream = InStream.forTextOnlyFixedWidthErrorReplace(
          ssrd.encodingInfo,
          ssrd.elementRuntimeData,
          jis, ssrd.encodingInfo.knownEncodingName, lengthLimitInBits)
        PState.createInitialState(rootERD,
          inStream,
          this)
      } else {
        PState.createInitialState(rootERD,
          input,
          this,
          bitOffset = 0,
          bitLengthLimit = lengthLimitInBits) // TODO also want to pass here the externally set variables, other flags/settings.
      }
    try {
      Debugger.init(ssrd.parser)
      parse(initialState)
    } finally {
      Debugger.fini(ssrd.parser)
    }
  }

  def parse(file: File): DFDL.ParseResult = {
    Assert.usage(!this.isError)

    val initialState =
      if (ssrd.encodingInfo.isScannable &&
        ssrd.encodingInfo.defaultEncodingErrorPolicy == EncodingErrorPolicy.Replace &&
        ssrd.knownEncodingIsFixedWidth) {
        // use simpler I/O layer
        val inStream = InStream.forTextOnlyFixedWidthErrorReplace(ssrd.encodingInfo,
          ssrd.elementRuntimeData,
          file, ssrd.knownEncodingName, -1)
        PState.createInitialState(ssrd.elementRuntimeData,
          inStream,
          this)
      } else {
        PState.createInitialState(ssrd.elementRuntimeData,
          FileChannel.open(file.toPath),
          this,
          bitOffset = 0,
          bitLengthLimit = file.length * 8) // TODO also want to pass here the externally set variables, other flags/settings.
      }
    try {
      Debugger.init(ssrd.parser)
      parse(initialState)
    } finally {
      Debugger.fini(ssrd.parser)
    }
  }

  def parse(initialState: PState): ParseResult = {
    ExecutionMode.usingRuntimeMode {
      val pr = new ParseResult(this) {
        val p = ssrd.parser
        val postParseState = { // Not lazy. We want to parse right now.
          try {
            p.parse1(initialState, ssrd.elementRuntimeData)
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
              val x = procErr
              Assert.invariantFailed("got a processing error that was not a parse error: %s. This is the parser!".format(x))
            }
            case sde: SchemaDefinitionError => {
              // A SDE was detected at runtime (perhaps due to a runtime-valued property like byteOrder or encoding)
              // These are fatal, and there's no notion of backtracking them, so they propagate to top level
              // here.
              initialState.failed(sde)
            }
            case rsde: RuntimeSchemaDefinitionError => {
              initialState.failed(rsde)
            }
            case e: ErrorAlreadyHandled => {
              initialState.failed(e.th)
              // Assert.invariantFailed("OOLAGException at runtime (should not happen). Caught at DataProcessor level: " + e)
            }
            case e: TunableLimitExceededError => {
              initialState.failed(e)
            }
          }
        }

        val resultState = {
          val finalState = validateResult(postParseState)
          finalState
        }

        lazy val isValidationSuccess = {
          val res = getValidationMode match {
            case ValidationMode.Off => true
            case _ => {
              val res = resultState.diagnostics.exists { d =>
                d match {
                  case ve: ValidationError => true
                  case _ => false
                }
              }
              res
            }
          }
          res
        }

      }
      pr
    }
  }
}

abstract class ParseResult(dp: DataProcessor)
  extends DFDL.ParseResult
  with WithDiagnosticsImpl {

  def resultState: PState
  protected def postParseState: PState
  def isValidationSuccess: Boolean

  /**
   * Xerces validation.
   */
  private def validateWithXerces(state: PState): Unit = {
    if (state.status == Success) {
      val schemaFileNames = state.infoset.asInstanceOf[InfosetElement].runtimeData.schemaFileNames
      Validator.validateXMLSources(schemaFileNames, result)
    } else {
      Assert.abort(new IllegalStateException("There is no result. Should check by calling isError() first."))
    }
  }

  /**
   * To be successful here, we need to capture xerces parse/validation
   * errors and add them to the Diagnostics list in the PState.
   *
   * @param state the initial parse state.
   * @return the final parse state with any validation diagnostics.
   */
  def validateResult(state: PState) = {
    val resultState =
      if (dp.getValidationMode == ValidationMode.Full) {
        val postValidateState =
          try {
            validateWithXerces(state)
            state
          } catch {
            case (spe: SAXParseException) =>
              state.withValidationErrorNoContext(spe.getMessage)

            case (se: SAXException) =>
              state.withValidationErrorNoContext(se.getMessage)

            case (ve: ValidationException) =>
              state.withValidationErrorNoContext(ve.getMessage)
          }
        postValidateState
      } else state

    resultState
  }

  lazy val result =
    if (postParseState.status == Success) {
      resultAsScalaXMLElement
    } else {
      Assert.abort(new IllegalStateException("There is no result. Should check by calling isError() first."))
    }

  lazy val resultAsScalaXMLElement =
    if (postParseState.status == Success) {
      val xmlClean = {
        val nodeSeq = postParseState.infoset.toXML
        val Seq(eNoHidden) = XMLUtils.removeHiddenElements(nodeSeq)
        val eNoAttribs = XMLUtils.removeAttributes(eNoHidden, Seq(XMLUtils.INT_NS_OBJECT))
        eNoAttribs
      }
      xmlClean
    } else {
      Assert.abort(new IllegalStateException("There is no result. Should check by calling isError() first."))
    }
}