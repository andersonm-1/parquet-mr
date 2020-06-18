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
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.crypto.ColumnEncryptionProperties;
import org.apache.parquet.crypto.DecryptionKeyRetriever;
import org.apache.parquet.crypto.DecryptionPropertiesFactory;
import org.apache.parquet.crypto.EncryptionPropertiesFactory;
import org.apache.parquet.crypto.FileDecryptionProperties;
import org.apache.parquet.crypto.FileEncryptionProperties;
import org.apache.parquet.crypto.ParquetCipher;
import org.apache.parquet.crypto.ParquetCryptoRuntimeException;
import org.apache.parquet.hadoop.api.WriteSupport.WriteContext;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.parquet.crypto.keytools.KeyToolkit.stringIsEmpty;

public class PropertiesDrivenCryptoFactory implements EncryptionPropertiesFactory, DecryptionPropertiesFactory {
  private static final Logger LOG = LoggerFactory.getLogger(PropertiesDrivenCryptoFactory.class);

  public static final String COLUMN_KEYS_PROPERTY_NAME = "encryption.column.keys";
  public static final String FOOTER_KEY_PROPERTY_NAME = "encryption.footer.key";
  public static final String ENCRYPTION_ALGORITHM_PROPERTY_NAME = "encryption.algorithm";
  public static final String PLAINTEXT_FOOTER_PROPERTY_NAME = "encryption.plaintext.footer";

  public static final int DEK_LENGTH = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public FileEncryptionProperties getFileEncryptionProperties(Configuration fileHadoopConfig, Path tempFilePath,
      WriteContext fileWriteContext) throws ParquetCryptoRuntimeException {

    String footerKeyId = fileHadoopConfig.getTrimmed(FOOTER_KEY_PROPERTY_NAME);
    String columnKeysStr = fileHadoopConfig.getTrimmed(COLUMN_KEYS_PROPERTY_NAME);

    // File shouldn't be encrypted
    if (stringIsEmpty(footerKeyId) && stringIsEmpty(columnKeysStr)) {
      LOG.debug("Unencrypted file: {}", tempFilePath);
      return null;
    }

    if (stringIsEmpty(footerKeyId)) {
      throw new ParquetCryptoRuntimeException("Undefined footer key");
    }

    FileKeyMaterialStore keyMaterialStore = null;
    boolean keyMaterialInternalStorage = fileHadoopConfig.getBoolean(KeyToolkit.KEY_MATERIAL_INTERNAL_PROPERTY_NAME, true);
    if (!keyMaterialInternalStorage) {
      try {
        keyMaterialStore = new HadoopFSKeyMaterialStore(tempFilePath.getFileSystem(fileHadoopConfig));
        keyMaterialStore.initialize(tempFilePath, fileHadoopConfig, false);
      } catch (IOException e) {
        throw new ParquetCryptoRuntimeException("Failed to get key material store", e);
      }
    }

    FileKeyWrapper keyWrapper = new FileKeyWrapper(fileHadoopConfig, keyMaterialStore);

    String algo = fileHadoopConfig.getTrimmed(ENCRYPTION_ALGORITHM_PROPERTY_NAME, ParquetCipher.AES_GCM_V1.toString());
    ParquetCipher cipher;
    try {
      cipher = ParquetCipher.valueOf(algo);
    } catch (IllegalArgumentException e) {
      throw new ParquetCryptoRuntimeException("Wrong encryption algorithm: " + algo);
    }

    byte[] footerKeyBytes = new byte[DEK_LENGTH];
    RANDOM.nextBytes(footerKeyBytes);
    byte[] footerKeyMetadata = keyWrapper.getEncryptionKeyMetadata(footerKeyBytes, footerKeyId, true);

    Map<ColumnPath, ColumnEncryptionProperties> encryptedColumns = getColumnEncryptionProperties(columnKeysStr, keyWrapper);

    String plaintextFooterStr = fileHadoopConfig.getTrimmed(PLAINTEXT_FOOTER_PROPERTY_NAME);
    boolean plaintextFooter = Boolean.parseBoolean(plaintextFooterStr);

    FileEncryptionProperties.Builder propertiesBuilder = FileEncryptionProperties.builder(footerKeyBytes)
        .withFooterKeyMetadata(footerKeyMetadata)
        .withAlgorithm(cipher)
        .withEncryptedColumns(encryptedColumns);

    if (plaintextFooter) {
      propertiesBuilder = propertiesBuilder.withPlaintextFooter();
    }

    if (null != keyMaterialStore) {
      keyMaterialStore.saveMaterial();
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("File encryption properties for {} - algo: {}; footer key id: {}; plaintext footer: {}; "
          + "internal key material: {}; encrypted columns: {}",
          tempFilePath, cipher, footerKeyId, plaintextFooter, keyMaterialInternalStorage, columnKeysStr);
    }

    return propertiesBuilder.build();
  }

  private Map<ColumnPath, ColumnEncryptionProperties> getColumnEncryptionProperties(String columnKeys,
      FileKeyWrapper keyWrapper) throws ParquetCryptoRuntimeException {
    if (stringIsEmpty(columnKeys)) {
      throw new ParquetCryptoRuntimeException("No column keys configured in " + COLUMN_KEYS_PROPERTY_NAME);
    }
    Map<ColumnPath, ColumnEncryptionProperties> encryptedColumns = new HashMap<ColumnPath, ColumnEncryptionProperties>();
    String keyToColumns[] = columnKeys.split(";");
    for (int i = 0; i < keyToColumns.length; ++i) {
      final String curKeyToColumns = keyToColumns[i].trim();
      if (curKeyToColumns.isEmpty()) {
        continue;
      }

      String[] parts = curKeyToColumns.split(":");
      if (parts.length != 2) {
        throw new ParquetCryptoRuntimeException("Incorrect key to columns mapping in " + COLUMN_KEYS_PROPERTY_NAME
            + ": [" + curKeyToColumns + "]");
      }

      String columnKeyId = parts[0].trim();
      if (columnKeyId.isEmpty()) {
        throw new ParquetCryptoRuntimeException("Empty key name in " + COLUMN_KEYS_PROPERTY_NAME);
      }

      String columnNamesStr = parts[1].trim();
      String[] columnNames = columnNamesStr.split(",");
      if (0 == columnNames.length) {
        throw new ParquetCryptoRuntimeException("No columns to encrypt defined for key: " + columnKeyId);
      }

      for (int j = 0; j < columnNames.length; ++j) {
        final String columnName = columnNames[j].trim();
        if (columnName.isEmpty()) {
          throw new ParquetCryptoRuntimeException("Empty column name in " + COLUMN_KEYS_PROPERTY_NAME + " for key: " + columnKeyId);
        }

        final ColumnPath columnPath = ColumnPath.fromDotString(columnName);
        if (encryptedColumns.containsKey(columnPath)) {
          throw new ParquetCryptoRuntimeException("Multiple keys defined for the same column: " + columnName);
        }

        byte[] columnKeyBytes = new byte[DEK_LENGTH];
        RANDOM.nextBytes(columnKeyBytes);
        byte[] columnKeyKeyMetadata =  keyWrapper.getEncryptionKeyMetadata(columnKeyBytes, columnKeyId, false);

        ColumnEncryptionProperties cmd = ColumnEncryptionProperties.builder(columnPath)
            .withKey(columnKeyBytes)
            .withKeyMetaData(columnKeyKeyMetadata)
            .build();
        encryptedColumns.put(columnPath, cmd);
      }
    }
    if (encryptedColumns.isEmpty()) {
      throw new ParquetCryptoRuntimeException("No column keys configured in " + COLUMN_KEYS_PROPERTY_NAME);
    }

    return encryptedColumns;
  }

  @Override
  public FileDecryptionProperties getFileDecryptionProperties(Configuration hadoopConfig, Path filePath)
      throws ParquetCryptoRuntimeException {

    FileKeyMaterialStore keyMaterialStore = null;
    boolean keyMaterialInternalStorage = hadoopConfig.getBoolean(KeyToolkit.KEY_MATERIAL_INTERNAL_PROPERTY_NAME, true);
    if (!keyMaterialInternalStorage) {
      try {
        keyMaterialStore = new HadoopFSKeyMaterialStore(filePath.getFileSystem(hadoopConfig));
        keyMaterialStore.initialize(filePath, hadoopConfig, false);
      } catch (IOException e) {
        throw new ParquetCryptoRuntimeException("Failed to get key material store", e);
      }
    }

    DecryptionKeyRetriever keyRetriever = new FileKeyUnwrapper(hadoopConfig, keyMaterialStore);

    if (LOG.isDebugEnabled()) {
      LOG.debug("File decryption properties for {} - internal key material: {}", filePath, keyMaterialInternalStorage);
    }

    return FileDecryptionProperties.builder()
        .withKeyRetriever(keyRetriever)
        .withPlaintextFilesAllowed()
        .build();
  }
}
