/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.daffodil.dsom

import org.apache.daffodil.exceptions.SchemaFileLocatable
import org.apache.daffodil.xml.XMLUtils
import org.apache.daffodil.exceptions.ThrowsSDE
import scala.xml.NamespaceBinding
import org.apache.daffodil.xml.NS
import org.apache.daffodil.oolag.OOLAG.OOLAGHost
import scala.xml.Node

/**
 * Anything that can be computed without reference to the point of use
 * or point of reference can be computed here on these factory objects.
 */
abstract class SchemaComponentFactory( final override val xml: Node,
  final override val schemaDocument: SchemaDocument)
  extends SchemaComponent
  with NestingLexicalMixin {

  final override def parent = schemaDocument

}

abstract class AnnotatedSchemaComponentFactory( final override val xml: Node,
  final override val schemaDocument: SchemaDocument)
  extends AnnotatedSchemaComponent
  with NestingLexicalMixin {

  final override def parent = schemaDocument

}

trait SchemaFileLocatableImpl
  extends SchemaFileLocatable {

  def xml: scala.xml.Node
  def schemaFile: Option[DFDLSchemaFile]
  def parent: SchemaComponent

  /**
   * Annotations can contain expressions, so we need to be able to compile them.
   *
   * We need our own instance so that the expression compiler has this schema
   * component as its context.
   */

  override lazy val lineAttribute: Option[String] = {
    val attrText = xml.attribute(XMLUtils.INT_NS, XMLUtils.LINE_ATTRIBUTE_NAME).map { _.text }
    if (attrText.isDefined) {
      attrText
    } else if (parent != null) parent.lineAttribute
    else None
  }

  final override lazy val columnAttribute = xml.attribute(XMLUtils.INT_NS, XMLUtils.COLUMN_ATTRIBUTE_NAME) map { _.text }

  final override lazy val fileAttribute: Option[String] = {
    val optAttrNode = schemaFile.map { _.node.attribute(XMLUtils.INT_NS, XMLUtils.FILE_ATTRIBUTE_NAME) }.flatten
    val optAttrText = optAttrNode.map { _.text }
    optAttrText
  }
}

trait CommonContextMixin
  extends NestingMixin { self: OOLAGHost with ThrowsSDE =>

  def parent: SchemaComponent

  lazy val schemaFile: Option[DFDLSchemaFile] = parent.schemaFile
  lazy val schemaSet: SchemaSet = parent.schemaSet
  def schemaDocument: SchemaDocument = parent.schemaDocument
  lazy val xmlSchemaDocument: XMLSchemaDocument = parent.xmlSchemaDocument
  lazy val schema: Schema = parent.schema
  def uriString: String = parent.uriString

  def xml: scala.xml.Node

  /**
   * Namespace scope for resolving QNames.
   *
   * We insist that the prefix "xsi" is properly defined for use
   * in xsi:nil attributes, which is how we represent nilled elements
   * when we convert to XML.
   */
  final lazy val namespaces = {
    val scope = xml.scope
    val foundXsiURI = scope.getURI("xsi")
    val xsiURI = XMLUtils.xsiURI.toString
    val newScope =
      (foundXsiURI, this) match {
        case (null, e: ElementBase) => new NamespaceBinding("xsi", xsiURI, scope)
        case (`xsiURI`, _) => scope
        case (s: String, _) => schemaDefinitionError("Prefix 'xsi' must be bound to the namespace '%s', but was bound to the namespace '%s'.", xsiURI, s)
        case (null, _) => scope
      }
    newScope
  }

  /**
   * This is the root, or basic target namespace. Every schema component
   * gets its target namespace from its xmlSchemaDocument.
   */
  def targetNamespace: NS = xmlSchemaDocument.targetNamespace

  final lazy val targetNamespacePrefix = xml.scope.getPrefix(targetNamespace.toString)

}