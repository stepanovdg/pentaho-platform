/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License, version 2.1 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * Copyright 2014 Pentaho Corporation. All rights reserved.
 */

package org.pentaho.platform.osgi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.karaf.main.Main;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.IPentahoSystemListener;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.context.SecurityContextHolder;
import org.springframework.security.providers.UsernamePasswordAuthenticationToken;

/**
 * This Pentaho SystemListener starts the Embedded Karaf framework to support OSGI in the platform.
 * <p/>
 * Created by nbaker on 7/29/14.
 */
public class KarafBoot implements IPentahoSystemListener {
  private Main main;
  Logger logger = LoggerFactory.getLogger( getClass() );
  private static boolean initialized;
  public static final String ORG_OSGI_FRAMEWORK_SYSTEM_PACKAGES_EXTRA = "org.osgi.framework.system.packages.extra";

  private static final String SYSTEM_PROP_OSX_APP_ROOT_DIR = "osx.app.root.dir";

  private static final String KARAF_DIR = "/system/karaf";

  @Override public boolean startup( IPentahoSession session ) {

    try {
      String solutionRootPath = PentahoSystem.getApplicationContext().getSolutionRootPath();

      File karafDir = new File( solutionRootPath + KARAF_DIR );

      if( !karafDir.exists() ) {

        logger.warn( "Karaf not found in standard dir of '" + ( solutionRootPath + KARAF_DIR ) + "' " );

        String osxAppRootDir = System.getProperty( SYSTEM_PROP_OSX_APP_ROOT_DIR );

        if ( !StringUtils.isEmpty( osxAppRootDir )  ) {

          logger.warn( "Given that the system property '" + SYSTEM_PROP_OSX_APP_ROOT_DIR + "' is set, we are in "
              + "a OSX .app context; we'll try looking for Karaf in the app's root dir '" + osxAppRootDir + "' " );

          File osxAppKarafDir = new File( osxAppRootDir + KARAF_DIR );

          if ( osxAppKarafDir.exists() ) {
            karafDir = osxAppKarafDir; // karaf found in the app's root dir
          }
        }
      }

      String root = karafDir.toURI().getPath();

      System.setProperty( "karaf.home", root );
      System.setProperty( "karaf.base", root );
      System.setProperty( "karaf.data", root + "/data" );
      System.setProperty( "karaf.history", root + "/data/history.txt" );
      System.setProperty( "karaf.instances", root + "/instances" );
      System.setProperty( "karaf.startLocalConsole", "false" );
      System.setProperty( "karaf.startRemoteShell", "true" );
      System.setProperty( "karaf.lock", "false" );
      System.setProperty( "karaf.etc", root + "/etc"  );
      System.setProperty( "felix.fileinstall.dir", root + "/etc"); // Default is '' which results in serious performance hit

      // Tell others like the pdi-osgi-bridge that there's already a karaf instance running so they don't start
      // their own.
      System.setProperty( "embedded.karaf.mode", "true" );


      // set the location of the log4j config file, since OSGI won't pick up the one in webapp
      
      System.setProperty( "log4j.configuration", new File(solutionRootPath + "/system/osgi/log4j.xml").toURI().toString() );
      // Setting ignoreTCL to true such that the OSGI classloader used to initialize log4j will be the
      // same one used when instatiating appenders.
      System.setProperty( "log4j.ignoreTCL", "true" );

      expandSystemPackages( root + "/etc/custom.properties");
      
      // Setup karaf instance configuration
      KarafInstance karafInstance = new KarafInstance( root );
      new KarafInstancePortFactory( root + "/etc/KarafPorts.yaml" ).process();
      
      //Define any additional karaf instance properties here using karafInstance.registerProperty
      karafInstance.start();
      
      // Wrap the startup of Karaf in a child thread which has explicitly set a bogus authentication. This is
      // work-around and issue with Karaf inheriting the Authenticaiton set on the main system thread due to the
      // InheritableThreadLocal backing the SecurityContext. By setting a fake authentication, calls to the
      // org.pentaho.platform.osgi.SpringSecurityLoginModule always challenge the user.
      Thread karafThread = new Thread( new Runnable() {

        @Override public void run() {
          // Bogus authentication
          SecurityContextHolder.getContext().setAuthentication( new UsernamePasswordAuthenticationToken(
              UUID.randomUUID().toString(), "" ) );
          main = new Main( new String[ 0 ] );
          try {
            main.launch();
          } catch ( Exception e ) {
            main = null;
            logger.error( "Error starting Karaf", e );
          }
        }
      } );
      karafThread.setDaemon( true );
      karafThread.run();
      karafThread.join();
    } catch ( Exception e ) {
      main = null;
      logger.error( "Error starting Karaf", e );
    }
    return main != null;
  }

  void expandSystemPackages( String s ) {

    File customFile = new File( s );
    if( !customFile.exists() ){
      logger.warn( "No custom.properties file for in karaf distribution.");
      return;
    }
    Properties properties = new Properties();
    FileInputStream inStream = null;
    try {
      inStream = new FileInputStream( customFile );
      properties.load( inStream );
    } catch ( IOException e ) {
      logger.error( "Not able to expand system.packages.extra properties due to an error loading custom.properties", e );
      return;
    } finally {
      IOUtils.closeQuietly( inStream );
    }

    properties = new SystemPackageExtrapolator().expandProperties( properties );
    System.setProperty( ORG_OSGI_FRAMEWORK_SYSTEM_PACKAGES_EXTRA,
        properties.getProperty( ORG_OSGI_FRAMEWORK_SYSTEM_PACKAGES_EXTRA ) );

//    FileOutputStream out = null;
//    try {
//      out = new FileOutputStream( customFile );
//      properties.store( out, "expanding osgi properties" );
//    } catch ( IOException e ) {
//      logger.error( "Not able to expand system.packages.extra properties due error saving custom.properties", e );
//    } finally {
//      IOUtils.closeQuietly( out );
//    }

  }

  @Override public void shutdown() {
    try {
      main.destroy();
    } catch ( Exception e ) {
      logger.error( "Error stopping Karaf", e );
    }
  }
}
