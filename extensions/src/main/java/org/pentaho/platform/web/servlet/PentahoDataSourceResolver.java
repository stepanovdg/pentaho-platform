/*
 * Copyright 2002 - 2017 Pentaho Corporation.  All rights reserved.
 *
 * This software was developed by Pentaho Corporation and is provided under the terms
 * of the Mozilla Public License, Version 1.1, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to http://www.mozilla.org/MPL/MPL-1.1.txt. TThe Initial Developer is Pentaho Corporation.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */

package org.pentaho.platform.web.servlet;

import mondrian.spi.DataSourceResolver;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.data.DBDatasourceServiceException;
import org.pentaho.platform.api.data.IDBDatasourceService;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.pentaho.platform.plugin.services.connections.mondrian.MDXOlap4jConnection;
import org.pentaho.platform.util.StringUtil;
import org.pentaho.platform.util.ThreadLocalUtils;
import org.pentaho.platform.web.servlet.messages.Messages;

import javax.sql.DataSource;
import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * This class provides SPI functionality to Mondrian. It resolves relational data sources by their name. It uses the
 * {@link PentahoSessionHolder}.
 *
 * @author Luc Boudreau
 */
public class PentahoDataSourceResolver implements DataSourceResolver {
  private static final Log logger = LogFactory.getLog( PentahoDataSourceResolver.class );

  public DataSource lookup( String dataSourceName ) throws Exception {
    Callable<DataSource> callable = new Callable<DataSource>() {
      public DataSource call() throws Exception {
        DataSource datasource = null;
        String unboundDsName = null;
        IDBDatasourceService datasourceSvc = null;
        try {
          datasourceSvc =
            PentahoSystem.getObjectFactory().get( IDBDatasourceService.class, PentahoSessionHolder.getSession() );
          unboundDsName = datasourceSvc.getDSUnboundName( dataSourceName );
          datasource = datasourceSvc.getDataSource( unboundDsName );
        } catch ( ObjectFactoryException e ) {
          logger.error( Messages.getInstance().getErrorString( "PentahoXmlaServlet.ERROR_0002_UNABLE_TO_INSTANTIATE" ),
            e ); //$NON-NLS-1$
          throw e;
        } catch ( DBDatasourceServiceException e ) {
          /* We tried to find the datasource using unbound name.
          ** Now as a fall back we will attempt to find this datasource as it is.
          ** For example jboss/datasource/Hibernate. The unbound name ends up to be Hibernate
          ** We will first look for Hibernate and if we fail then look for jboss/datasource/Hibernate */

          logger.warn( Messages.getInstance().getString(
            "PentahoXmlaServlet.WARN_0001_UNABLE_TO_FIND_UNBOUND_NAME", dataSourceName, unboundDsName ),
            e ); //$NON-NLS-1$
          try {
            datasource = datasourceSvc.getDataSource( dataSourceName );
          } catch ( DBDatasourceServiceException dbse ) {
            logger
              .error( Messages.getInstance().getErrorString( "PentahoXmlaServlet.ERROR_0002_UNABLE_TO_INSTANTIATE" ),
                e ); //$NON-NLS-1$
            throw dbse;
          }
        }
        return datasource;

      }
    };
    return checkJdbcProperties( callable );
  }

  private DataSource checkJdbcProperties( Callable<DataSource> callable ) throws Exception {
    Properties properties = DataSourceResolver.extraJdbcProperties.get();
    if ( properties != null ) {
      if ( !StringUtil.isEmpty( properties.getProperty( MDXOlap4jConnection.PENTAHO_USER ) ) ) {
        return SecurityHelper.getInstance()
          .runAsUser( properties.getProperty( MDXOlap4jConnection.PENTAHO_USER ), callable );
      }
    }
    return callable.call();
  }
}
