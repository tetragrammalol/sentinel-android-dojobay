package com.samourai.wallet.api.explorer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplorerApi {
  private Logger log = LoggerFactory.getLogger(ExplorerApi.class);

  private static final String URL_TX = "tx/";
  private static final String URL_ADDRESS = "address/";

  private boolean testnet;

  public ExplorerApi(boolean testnet) {
    this.testnet = testnet;
  }

  /**
   * Sentinel resolves explorers in the app. This helper no longer
   * knows a public host, so a caller cannot leak a lookup to one.
   */
  public String getUrl() {
    throw new UnsupportedOperationException("No explorer host is configured");
  }

  public String getUrlTx() {
    return getUrl()+URL_TX;
  }

  public String getUrlTx(String txid) {
    return getUrl()+URL_TX+txid;
  }

  public String getUrlAddress() {
    return getUrl()+URL_ADDRESS;
  }

  public String getUrlAddress(String address) {
    return getUrl()+URL_ADDRESS+address;
  }
}
