/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.connector.catalog

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.internal.{SQLConf, StaticSQLConf}

/**
 * A trait to encapsulate catalog lookup function and helpful extractors.
 */
private[sql] trait LookupCatalog extends Logging {

  protected val catalogManager: CatalogManager

  /**
   * Returns the current catalog set.
   */
  def currentCatalog: CatalogPlugin = catalogManager.currentCatalog

  /**
   * Extract catalog plugin and remaining identifier names.
   *
   * This does not substitute the default catalog if no catalog is set in the identifier.
   */
  private object CatalogAndMultipartIdentifier {
    def unapply(parts: Seq[String]): Some[(Option[CatalogPlugin], Seq[String])] = parts match {
      case Seq(_) =>
        Some((None, parts))
      case Seq(catalogName, tail @ _*) =>
        try {
          Some((Some(catalogManager.catalog(catalogName)), tail))
        } catch {
          case _: CatalogNotFoundException =>
            Some((None, parts))
        }
    }
  }

  /**
   * Extract session catalog and identifier from a multi-part identifier.
   */
  object SessionCatalogAndIdentifier {
    def unapply(parts: Seq[String]): Option[(CatalogPlugin, Identifier)] = parts match {
      case CatalogAndIdentifier(catalog, ident) if CatalogV2Util.isSessionCatalog(catalog) =>
        Some(catalog, ident)
      case _ => None
    }
  }

  /**
   * Extract non-session catalog and identifier from a multi-part identifier.
   */
  object NonSessionCatalogAndIdentifier {
    def unapply(parts: Seq[String]): Option[(CatalogPlugin, Identifier)] = parts match {
      case CatalogAndIdentifier(catalog, ident) if !CatalogV2Util.isSessionCatalog(catalog) =>
        Some(catalog, ident)
      case _ => None
    }
  }

  /**
   * Extract catalog and namespace from a multi-part name with the current catalog if needed.
   * Catalog name takes precedence over namespaces.
   */
  object CatalogAndNamespace {
    def unapply(nameParts: Seq[String]): Some[(CatalogPlugin, Seq[String])] = {
      assert(nameParts.nonEmpty)
      try {
        Some((catalogManager.catalog(nameParts.head), nameParts.tail))
      } catch {
        case _: CatalogNotFoundException =>
          Some((currentCatalog, nameParts))
      }
    }
  }

  /**
   * Extract catalog and identifier from a multi-part name with the current catalog if needed.
   * Catalog name takes precedence over identifier, but for a single-part name, identifier takes
   * precedence over catalog name.
   */
  object CatalogAndIdentifier {
    import org.apache.spark.sql.connector.catalog.CatalogV2Implicits.MultipartIdentifierHelper

    private val globalTempDB = SQLConf.get.getConf(StaticSQLConf.GLOBAL_TEMP_DATABASE)

    def unapply(nameParts: Seq[String]): Option[(CatalogPlugin, Identifier)] = {
      assert(nameParts.nonEmpty)
      if (nameParts.length == 1) {
        // If the current catalog is session catalog, the current namespace is not used because
        // the single-part name could be referencing a temp view, which doesn't belong to any
        // namespaces. An empty namespace will be resolved inside the session catalog
        // implementation when a relation is looked up.
        val ns = if (CatalogV2Util.isSessionCatalog(currentCatalog)) {
          Array.empty[String]
        } else {
          catalogManager.currentNamespace
        }
        Some((currentCatalog, Identifier.of(ns, nameParts.head)))
      } else if (nameParts.head.equalsIgnoreCase(globalTempDB)) {
        // Conceptually global temp views are in a special reserved catalog. However, the v2 catalog
        // API does not support view yet, and we have to use v1 commands to deal with global temp
        // views. To simplify the implementation, we put global temp views in a special namespace
        // in the session catalog. The special namespace has higher priority during name resolution.
        // For example, if the name of a custom catalog is the same with `GLOBAL_TEMP_DATABASE`,
        // this custom catalog can't be accessed.
        Some((catalogManager.v2SessionCatalog, nameParts.asIdentifier))
      } else {
        try {
          Some((catalogManager.catalog(nameParts.head), nameParts.tail.asIdentifier))
        } catch {
          case _: CatalogNotFoundException =>
            Some((currentCatalog, nameParts.asIdentifier))
        }
      }
    }
  }

  /**
   * Extract legacy table identifier from a multi-part identifier.
   *
   * For legacy support only. Please use [[CatalogAndIdentifier]] instead on DSv2 code paths.
   */
  object AsTableIdentifier {
    def unapply(parts: Seq[String]): Option[TableIdentifier] = parts match {
      case CatalogAndMultipartIdentifier(None, names)
          if CatalogV2Util.isSessionCatalog(currentCatalog) =>
        names match {
          case Seq(name) =>
            Some(TableIdentifier(name))
          case Seq(database, name) =>
            Some(TableIdentifier(name, Some(database)))
          case _ =>
            None
        }
      case _ =>
        None
    }
  }
}
