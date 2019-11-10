/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.parquet.crypto.keytools;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.crypto.AesDecryptor;
import org.apache.parquet.crypto.AesEncryptor;
import org.apache.parquet.crypto.DecryptionKeyRetriever;
import org.apache.parquet.crypto.KeyAccessDeniedException;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;


public class WrappedKeyManager extends FileKeyManager {
  
  private static final String wrappingMethod = "org.apache.parquet.crypto.keytools.WrappedKeyManager";
  private static final String wrappingMethodVersion = "0.1";
  
  private static final String WRAPPING_METHOD_FIELD = "method";
  private static final String WRAPPING_METHOD_VERSION_FIELD = "version";
  private static final String MASTER_KEY_ID_FIELD = "masterKeyID";
  private static final String KMS_INSTANCE_ID_FIELD = "kmsInstanceID";
  private static final String WRAPPED_KEY_FIELD = "wrappedKey";

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private Configuration hadoopConfiguration;
  private boolean wrapLocally;
  private KeyMaterialStore keyMaterialStore;
  private String fileID;

  private SecureRandom random;
  private short keyCounter;


  public static class WrappedKeyRetriever implements DecryptionKeyRetriever {
    private KmsClient kmsClient;
    private String kmsInstanceID;
    private final boolean unwrapLocally;
    private final KeyMaterialStore keyMaterialStore;
    private final String fileID;
    private final Configuration hadoopConfiguration;

    private WrappedKeyRetriever(KmsClient kmsClient, String kmsInstanceID, Configuration hadoopConfiguration, boolean unwrapLocally,
                                KeyMaterialStore keyMaterialStore, String fileID) {
      this.kmsClient = kmsClient;
      this.kmsInstanceID = kmsInstanceID;
      this.hadoopConfiguration = hadoopConfiguration;
      this.keyMaterialStore = keyMaterialStore;
      this.fileID = fileID;
      this.unwrapLocally = unwrapLocally;
    }

    @Override
    public byte[] getKey(byte[] keyMetaData) throws IOException, KeyAccessDeniedException {
      String keyMaterial;
      if (null != keyMaterialStore) {
        String keyIDinFile = new String(keyMetaData, StandardCharsets.UTF_8);
        keyMaterial = keyMaterialStore.getKeyMaterial(fileID, keyIDinFile);
      }
      else {
        keyMaterial = new String(keyMetaData, StandardCharsets.UTF_8);
      }

      Map<String, String> keyMaterialJson = null;
      try {
        keyMaterialJson = objectMapper.readValue(new StringReader(keyMaterial),
                new TypeReference<Map<String, String>>() {});
      } catch (JsonProcessingException e) {
        throw new IOException("Failed to parse key material " + keyMaterial, e);
      }
      
      String wrapMethod = keyMaterialJson.get(WRAPPING_METHOD_FIELD);
      if (!wrappingMethod.equals(wrapMethod)) {
        throw new IOException("Wrong wrapping method " + wrapMethod);
      }
      
      //String wrapMethodVersion = (String) jsonObject.get(WRAPPING_METHOD_VERSION_FIELD);
      //TODO compare to wrappingMethodVersion
          
      String encodedWrappedDatakey = keyMaterialJson.get(WRAPPED_KEY_FIELD);
      String masterKeyID = keyMaterialJson.get(MASTER_KEY_ID_FIELD);
      if (null == kmsClient) {
        kmsInstanceID = keyMaterialJson.get(KMS_INSTANCE_ID_FIELD);
        kmsClient = getKmsClient(this.hadoopConfiguration, kmsInstanceID);
      }
      
      byte[] dataKey = null;
      if (unwrapLocally) {
        byte[] wrappedDataKey = Base64.getDecoder().decode(encodedWrappedDatakey);
        byte[] masterKey = null;
        try {
          masterKey = kmsClient.getKeyFromServer(masterKeyID);
        }
        catch (UnsupportedOperationException e) {
          throw new IOException("KMS client doesnt support key fetching", e);
        }
        if (null == masterKey) {
          throw new IOException("Failed to get from KMS the master key " + masterKeyID);
        }
        AesDecryptor keyDecryptor = new AesDecryptor(AesEncryptor.Mode.GCM, masterKey, null);
        dataKey = keyDecryptor.decrypt(wrappedDataKey, 0, wrappedDataKey.length, null);
        wipeKey(masterKey);
      }
      else {
        try {
          dataKey = kmsClient.unwrapDataKeyInServer(encodedWrappedDatakey, masterKeyID);
        }
        catch (UnsupportedOperationException e) {
          throw new IOException("KMS client doesnt support key wrapping", e);
        }
        if (null == dataKey) {
          throw new IOException("Failed to unwrap in KMS with master key " + masterKeyID);
        }
      }
      return dataKey;
    }
  }

  @Override
  public void initialize(Configuration configuration, KmsClient kmsClient, KeyMaterialStore keyMaterialStore,
                         String fileID) throws IOException {
    String localWrap = configuration.getTrimmed("encryption.wrap.locally");
    if (null == localWrap || localWrap.equalsIgnoreCase("true")) {
      wrapLocally = true; // true by default
    }
    else if (localWrap.equalsIgnoreCase("false")) {
      wrapLocally = false;
    }
    else {
      throw new IOException("Bad encryption.wrap.locally value: " + localWrap);
    }
    this.kmsClient = kmsClient;
    this.hadoopConfiguration = configuration;
    this.keyMaterialStore = keyMaterialStore;
    this.fileID = fileID;
    random = new SecureRandom();
    keyCounter = 0;
  }

  @Override
  public KeyWithMetadata getFooterEncryptionKey(String footerMasterKeyID) throws IOException {
    return generateDataKey(footerMasterKeyID, true);
  }

  @Override
  public KeyWithMetadata getColumnEncryptionKey(ColumnPath column, String columnMasterKeyID) throws IOException {
    return generateDataKey(columnMasterKeyID, false);
  }
  
  @Override
  public DecryptionKeyRetriever getDecryptionKeyRetriever() {
    return new WrappedKeyRetriever(kmsClient, kmsInstanceID, hadoopConfiguration, wrapLocally, keyMaterialStore, fileID);
  }

  @Override
  public void close() {
    // TODO Wipe keys
  }

  /**
   * Generates random data encryption key, and creates its metadata.
   * The metadata is comprised of the wrapped data key (encrypted with master key), and the identity of the master key.
   * @param masterKeyID
   * @return
   * @throws IOException
   */
  private KeyWithMetadata generateDataKey(String masterKeyID, boolean addKMSInstance) throws IOException {
    if (null == kmsClient) {
      throw new IOException("No KMS client available.");
    }
    byte[] dataKey = new byte[16]; //TODO length. configure via properties
    random.nextBytes(dataKey);
    String encodedWrappedDataKey = null;
    if (wrapLocally) {
      byte[] masterKey;
      try {
        masterKey = kmsClient.getKeyFromServer(masterKeyID);
      } 
      catch (KeyAccessDeniedException e) {
        throw new IOException("Unauthorized to fetch key: " + masterKeyID, e);
      } 
      catch (UnsupportedOperationException e) {
        throw new IOException("KMS client doesnt support key fetching", e);
      }
      AesEncryptor keyEncryptor = new AesEncryptor(AesEncryptor.Mode.GCM, masterKey, null);
      byte[] wrappedDataKey = keyEncryptor.encrypt(false, dataKey, null);
      wipeKey(masterKey);
      encodedWrappedDataKey = Base64.getEncoder().encodeToString(wrappedDataKey);
    }
    else {
      if (!kmsClient.supportsServerSideWrapping()) {
        throw new UnsupportedOperationException("KMS client doesn't support server-side wrapping");
      }
      try {
        encodedWrappedDataKey = kmsClient.wrapDataKeyInServer(dataKey, masterKeyID);
      } 
      catch (KeyAccessDeniedException e) {
        throw new IOException("Unauthorized to wrap with master key: " + masterKeyID, e);
      } 
      catch (UnsupportedOperationException e) {
        throw new IOException("KMS client doesnt support key wrapping", e);
      }
    }
    Map<String, String> keyMaterialMap = new HashMap<String, String>(4);
    keyMaterialMap.put(WRAPPING_METHOD_FIELD, wrappingMethod);
    keyMaterialMap.put(WRAPPING_METHOD_VERSION_FIELD, wrappingMethodVersion);
    keyMaterialMap.put(MASTER_KEY_ID_FIELD, masterKeyID);
    if (addKMSInstance) {
      keyMaterialMap.put(KMS_INSTANCE_ID_FIELD, kmsInstanceID);
    }
    keyMaterialMap.put(WRAPPED_KEY_FIELD, encodedWrappedDataKey);
    String keyMaterial = objectMapper.writeValueAsString(keyMaterialMap);
        
    byte[] keyMetadata = null;
    if (null != keyMaterialStore) {
      String keyName = "k" + keyCounter;
      keyMaterialStore.storeKeyMaterial(keyMaterial, fileID, keyName);
      keyMetadata = keyName.getBytes(StandardCharsets.UTF_8);
      keyCounter++;
    }
    else {
      keyMetadata  = keyMaterial.getBytes(StandardCharsets.UTF_8);
    }
    KeyWithMetadata key = new KeyWithMetadata(dataKey, keyMetadata);
    return key;
  }
}
