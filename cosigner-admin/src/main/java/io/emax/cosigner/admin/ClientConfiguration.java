package io.emax.cosigner.admin;

import io.emax.cosigner.common.EnvironmentVariableParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

public class ClientConfiguration {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientConfiguration.class);
  private static boolean configLoaded = false;

  // Configuration data
  private static String rsServerUrl = "https://localhost:8445";
  private static boolean useTls = true;
  private static String tlsKeystore = "./cosigner.jks";
  private static String tlsKeystorePassword = "cosigner";
  private static String tlsCertAlias = "cosigner";

  public String getRsServerUrl() {
    return rsServerUrl;
  }

  public boolean useTls() {
    return useTls;
  }

  public String getTlsKeystore() {
    return tlsKeystore;
  }

  public String getTlsKeystorePassword() {
    return tlsKeystorePassword;
  }

  public String getTlsCertAlias() {
    return tlsCertAlias;
  }

  public static void setRsServerUrl(String rsServerUrl) {
    ClientConfiguration.rsServerUrl = rsServerUrl;
  }

  private static synchronized void loadConfig() {
    if (!configLoaded) {
      FileInputStream propertiesFile = null;
      try {
        // Get the config file
        String propertiesFilePath = "./cosigner-admin.properties";
        Properties cosignerProperties = new Properties();
        propertiesFile = new FileInputStream(propertiesFilePath);
        cosignerProperties.load(propertiesFile);
        propertiesFile.close();

        // Load config
        // rsServerUrl
        rsServerUrl = EnvironmentVariableParser
            .resolveEnvVars(cosignerProperties.getProperty("rsServerUrl", rsServerUrl));

        // tlsKeystore
        tlsKeystore = EnvironmentVariableParser
            .resolveEnvVars(cosignerProperties.getProperty("tlsKeystore", tlsKeystore));

        // tlsKeystorePassword
        tlsKeystorePassword = EnvironmentVariableParser.resolveEnvVars(
            cosignerProperties.getProperty("tlsKeystorePassword", tlsKeystorePassword));

        // tlsCertAlias
        tlsCertAlias = EnvironmentVariableParser
            .resolveEnvVars(cosignerProperties.getProperty("tlsCertAlias", tlsCertAlias));

        // useTls
        useTls = Boolean.parseBoolean(EnvironmentVariableParser
            .resolveEnvVars(cosignerProperties.getProperty("useTls", String.valueOf(useTls))));

      } catch (IOException e) {
        if (propertiesFile != null) {
          try {
            propertiesFile.close();
          } catch (IOException e1) {
            StringWriter errors = new StringWriter();
            e1.printStackTrace(new PrintWriter(errors));
            LOGGER.warn(errors.toString());
          }
        }
        LOGGER.info("Could not load cosigner-admin configuration, using defaults.");
      }
      configLoaded = true;
    }
  }

  public ClientConfiguration() {
    loadConfig();
  }
}

