package com.samourai.boltzmann.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.samourai.boltzmann.beans.Txos;
import java.io.File;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OxtFetch {
  private static final Logger log = LoggerFactory.getLogger(OxtFetch.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public OxtFetch() {}

  public Txos fetch(String txid) throws Exception {
    Map<String, Long> ins0 = new LinkedHashMap<String, Long>();
    Map<String, Long> outs0 = new LinkedHashMap<String, Long>();

    JsonNode obj;

    URL localResource = OxtFetch.class.getResource("/fetchTx/" + txid + ".json");
    if (localResource != null) {
      // read from local file
      File localJson = new File(localResource.getFile());
      obj = objectMapper.readTree(localJson);
    } else {
      throw new RuntimeException("No local transaction fixture for " + txid);
    }
    JsonNode dataObj = obj.withArray("data").get(0);

    JsonNode inputs = dataObj.withArray("ins");
    for (JsonNode input : inputs) {
      String amount = input.get("amount").asText();
      long value = Long.valueOf(amount);
      ins0.put(input.withArray("addresses").get(0).get("value").asText(), value);
    }

    JsonNode outputs = dataObj.withArray("outs");
    for (JsonNode output : outputs) {
      String amount = output.get("amount").asText();
      long value = Long.valueOf(amount);
      outs0.put(output.withArray("addresses").get(0).get("value").asText(), value);
    }

    Txos txos = new Txos(ins0, outs0);
    if (log.isDebugEnabled()) {
      log.debug("# Fetched " + txid + ":");
      log.debug(txos.getInputs().size() + " inputs: " + txos.getInputs());
      log.debug(txos.getOutputs().size() + " outputs: " + txos.getOutputs());
    }
    return txos;
  }
}
