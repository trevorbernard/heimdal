package io.emax.cosigner.bitcoin.common;

import io.emax.cosigner.bitcoin.BitcoinResource;
import io.emax.cosigner.bitcoin.bitcoindrpc.BlockChainName;
import io.emax.cosigner.bitcoin.bitcoindrpc.NetworkBytes;
import io.emax.cosigner.common.Base58;
import io.emax.cosigner.common.ByteUtilities;
import io.emax.cosigner.common.DeterministicRng;
import io.emax.cosigner.common.crypto.Secp256k1;

import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

public class BitcoinTools {

  private static final Logger LOGGER = LoggerFactory.getLogger(BitcoinTools.class);
  public static final String NOKEY = "NOKEY";
  private static final String SHA256 = "SHA-256";

  /**
   * Generate a deterministic set of private keys based on a secret key.
   *
   * @param userKeyPart   Expect these to be hex strings without the leading 0x identifier. When
   *                      combined with serverKeyPart, it provides the seed for the private keys.
   * @param serverKeyPart Expect these to be hex strings without the leading 0x identifier. When
   *                      combined with userKeyPart, it provides the seed for the private keys.
   * @param rounds        Number of keys to skip when generating the private key.
   * @return The private key that this data generates.
   */
  public static String getDeterministicPrivateKey(String userKeyPart, String serverKeyPart,
      int rounds) {
    if (userKeyPart == null) {
      return NOKEY;
    }

    try {
      byte[] userKey = new BigInteger(userKeyPart, 16).toByteArray();
      byte[] serverKey = new BigInteger(serverKeyPart, 16).toByteArray();
      SecureRandom secureRandom = DeterministicRng.getSecureRandom(userKey, serverKey);

      // Generate the key, skipping as many as desired.
      byte[] privateKeyAttempt = new byte[32];
      for (int i = 0; i < Math.max(rounds, 1); i++) {
        secureRandom.nextBytes(privateKeyAttempt);
        BigInteger privateKeyCheck = new BigInteger(1, privateKeyAttempt);
        while (privateKeyCheck.compareTo(BigInteger.ZERO) == 0
            || privateKeyCheck.compareTo(Secp256k1.MAXPRIVATEKEY) == 1) {
          secureRandom.nextBytes(privateKeyAttempt);
          privateKeyCheck = new BigInteger(1, privateKeyAttempt);
        }
      }

      return encodePrivateKey(ByteUtilities.toHexString(privateKeyAttempt));
    } catch (RuntimeException e) {
      LOGGER.debug(null, e);
      return NOKEY;
    }
  }

  /**
   * Encodes a raw public key in a bitcoind compatible format.
   */
  public static String encodePrivateKey(String privateKeyString) {
    String networkBytes;
    try {
      networkBytes = BitcoinResource.getResource().getBitcoindRpc().getblockchaininfo().getChain()
          == BlockChainName.main ? NetworkBytes.PRIVATEKEY.toString() :
          NetworkBytes.PRIVATEKEY_TEST.toString();
    } catch (Exception e) {
      LOGGER.debug("No network connection, assuming regular network", e);
      networkBytes = NetworkBytes.PRIVATEKEY.toString();
    }

    // Encode in format bitcoind is expecting
    byte[] privateKeyAttempt = ByteUtilities.toByteArray(privateKeyString);
    byte[] privateKey = ByteUtilities.toByteArray(networkBytes);
    byte[] privateKey2 = new byte[privateKey.length + privateKeyAttempt.length];
    System.arraycopy(privateKey, 0, privateKey2, 0, privateKey.length);
    System
        .arraycopy(privateKeyAttempt, 0, privateKey2, privateKey.length, privateKeyAttempt.length);
    privateKey = new byte[privateKey2.length];
    System.arraycopy(privateKey2, 0, privateKey, 0, privateKey2.length);

    try {
      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.update(privateKey);
      byte[] checksumHash = Arrays.copyOfRange(md.digest(md.digest()), 0, 4);

      privateKey2 = new byte[privateKey.length + checksumHash.length];
      System.arraycopy(privateKey, 0, privateKey2, 0, privateKey.length);
      System.arraycopy(checksumHash, 0, privateKey2, privateKey.length, checksumHash.length);
      privateKey = new byte[privateKey2.length];
      System.arraycopy(privateKey2, 0, privateKey, 0, privateKey2.length);

      return Base58.encode(privateKey);
    } catch (NoSuchAlgorithmException e) {
      LOGGER.error(null, e);
      return NOKEY;
    }

  }

  /**
   * Encodes the userKey secret so that it can be referenced and stored in bitcoind's wallet without
   * revealing what the original value is.
   *
   * @param key User key secret value.
   * @return Encoded/hashed version of the key.
   */
  public static String encodeUserKey(String key) {
    try {
      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.update(key.getBytes("UTF-8"));
      return new BigInteger(md.digest()).toString(16);
    } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
      LOGGER.error(null, e);
      return null;
    }
  }

  /**
   * Convert a key into its corresponding public address.
   *
   * @param key          Key to convert
   * @param isPrivateKey Is this private or public
   * @return Public bitcoin address.
   */
  public static String getPublicAddress(String key, boolean isPrivateKey) {
    try {
      byte[] publicKeyBytes;
      if (isPrivateKey) {
        publicKeyBytes = getPublicKeyBytes(key);
      } else {
        publicKeyBytes = ByteUtilities.toByteArray(key);
      }

      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.reset();
      md.update(publicKeyBytes);
      byte[] publicShaKeyBytes = md.digest();

      RIPEMD160Digest ripemd = new RIPEMD160Digest();
      byte[] publicRipemdKeyBytes = new byte[20];
      ripemd.update(publicShaKeyBytes, 0, publicShaKeyBytes.length);
      ripemd.doFinal(publicRipemdKeyBytes, 0);

      // Add network bytes
      String networkBytes =
          BitcoinResource.getResource().getBitcoindRpc().getblockchaininfo().getChain()
              == BlockChainName.main ? NetworkBytes.P2PKH.toString() :
              NetworkBytes.P2PKH_TEST.toString();

      byte[] networkPublicKeyBytes = ByteUtilities.toByteArray(networkBytes);
      byte[] networkPublicKeyBytes2 =
          new byte[networkPublicKeyBytes.length + publicRipemdKeyBytes.length];
      System.arraycopy(networkPublicKeyBytes, 0, networkPublicKeyBytes2, 0,
          networkPublicKeyBytes.length);
      System
          .arraycopy(publicRipemdKeyBytes, 0, networkPublicKeyBytes2, networkPublicKeyBytes.length,
              publicRipemdKeyBytes.length);
      networkPublicKeyBytes = new byte[networkPublicKeyBytes2.length];
      System.arraycopy(networkPublicKeyBytes2, 0, networkPublicKeyBytes, 0,
          networkPublicKeyBytes2.length);

      md = MessageDigest.getInstance(SHA256);
      md.reset();
      md.update(networkPublicKeyBytes);
      byte[] publicKeyChecksum = Arrays.copyOfRange(md.digest(md.digest()), 0, 4);

      byte[] decodedPublicKey = new byte[networkPublicKeyBytes.length + publicKeyChecksum.length];
      System.arraycopy(networkPublicKeyBytes, 0, decodedPublicKey, 0, networkPublicKeyBytes.length);
      System.arraycopy(publicKeyChecksum, 0, decodedPublicKey, networkPublicKeyBytes.length,
          publicKeyChecksum.length);

      return Base58.encode(decodedPublicKey);
    } catch (Exception e) {
      LOGGER.error("Unable to get network information when creating address", e);
      return null;
    }
  }

  /**
   * Decodes a bitcoin address and returns the RIPEMD-160 that it contains.
   *
   * @param address Bitcoin address
   * @return RIPEMD-160 hash of the public key.
   */
  public static String decodeAddress(String address) {
    try {
      byte[] decodedNetworkAddress = Base58.decode(address);
      byte[] networkBytes = ByteUtilities.readBytes(decodedNetworkAddress, 0, 1);
      byte[] addressBytes =
          ByteUtilities.readBytes(decodedNetworkAddress, 1, decodedNetworkAddress.length - 5);

      String checksumString =
          ByteUtilities.toHexString(networkBytes) + ByteUtilities.toHexString(addressBytes);
      byte[] checksumData = ByteUtilities.toByteArray(checksumString);

      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.reset();
      byte[] calculatedCheckum = Arrays.copyOfRange(md.digest(md.digest(checksumData)), 0, 4);
      LOGGER.debug("Address: " + address);
      LOGGER.debug("DecodedAddress: " + ByteUtilities.toHexString(decodedNetworkAddress));
      LOGGER.debug("NetworkBytes: " + ByteUtilities.toHexString(networkBytes));
      LOGGER.debug("AddressBytes: " + ByteUtilities.toHexString(addressBytes));

      byte[] checksumBytes =
          ByteUtilities.readBytes(decodedNetworkAddress, decodedNetworkAddress.length - 4, 4);
      LOGGER.debug("ChecksumBytes: " + ByteUtilities.toHexString(checksumBytes));
      LOGGER.debug("CalculatedChecksum: " + ByteUtilities.toHexString(calculatedCheckum));
      if (!ByteUtilities.toHexString(calculatedCheckum)
          .equalsIgnoreCase(ByteUtilities.toHexString(checksumBytes))) {
        LOGGER.debug("Badchecksum on: " + ByteUtilities.toHexString(addressBytes));
        return "";
      }
      return ByteUtilities.toHexString(addressBytes);
    } catch (Exception e) {
      LOGGER.error(null, e);
      return "";
    }
  }

  /**
   * Converts a RIPEMD-160 address to a base58 encoded one with checksums.
   *
   * @param addressBytes RIPEMD-160 address
   * @param networkBytes Network bytes that identify which network this address belongs to.
   * @return Address that bitcoind can import.
   */
  public static String encodeAddress(String addressBytes, String networkBytes) {
    try {
      String encodedBytes = networkBytes + addressBytes;
      byte[] data = ByteUtilities.toByteArray(encodedBytes);
      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.reset();
      md.update(data);
      byte[] publicKeyChecksum = Arrays.copyOfRange(md.digest(md.digest()), 0, 4);

      encodedBytes = encodedBytes + ByteUtilities.toHexString(publicKeyChecksum);
      encodedBytes = encodedBytes.toLowerCase(Locale.US);
      encodedBytes = Base58.encode(ByteUtilities.toByteArray(encodedBytes));
      return encodedBytes;
    } catch (Exception e) {
      LOGGER.error(null, e);
      return null;
    }
  }

  /**
   * Decodes an address and checks if it's a P2SH.
   *
   * @param address Bitcoin address
   * @return True if it's a P2SH address, false otherwise.
   */
  public static boolean isMultiSigAddress(String address) {
    try {
      // If the address isn't valid.
      if (decodeAddress(address).isEmpty()) {
        return false;
      }

      byte[] decodedNetworkAddress = Base58.decode(address);
      byte[] networkBytes = ByteUtilities.readBytes(decodedNetworkAddress, 0, 1);

      String networkString = ByteUtilities.toHexString(networkBytes);
      return networkString.equalsIgnoreCase(NetworkBytes.P2SH.toString()) || networkString
          .equalsIgnoreCase(NetworkBytes.P2SH_TEST.toString());

    } catch (Exception e) {
      LOGGER.debug(null, e);
      return false;
    }
  }

  public static String getPublicKey(String privateKey) {
    return ByteUtilities.toHexString(getPublicKeyBytes(privateKey));
  }

  /**
   * Converts a bitcoin-encoded private key to its corresponding public key.
   *
   * @param privateKey Bitcoin-encoded private key.
   * @return ECDSA public key.
   */
  public static byte[] getPublicKeyBytes(String privateKey) {
    try {
      byte[] decodedPrivateKey = Base58.decode(privateKey);
      byte[] networkPrivateKeyBytes = new byte[decodedPrivateKey.length - 4];
      byte[] privateKeyChecksum = new byte[4];

      System
          .arraycopy(decodedPrivateKey, 0, networkPrivateKeyBytes, 0, decodedPrivateKey.length - 4);
      System.arraycopy(decodedPrivateKey, decodedPrivateKey.length - 4, privateKeyChecksum, 0, 4);

      // Is it valid?
      MessageDigest md = MessageDigest.getInstance(SHA256);
      md.update(networkPrivateKeyBytes);
      byte[] checksumCheck = Arrays.copyOfRange(md.digest(md.digest()), 0, 4);
      for (int i = 0; i < 4; i++) {
        if (privateKeyChecksum[i] != checksumCheck[i]) {
          return new byte[0];
        }
      }

      // Strip leading network byte and get the public key
      byte[] privateKeyBytes =
          Arrays.copyOfRange(networkPrivateKeyBytes, 1, networkPrivateKeyBytes.length);
      return Secp256k1.getPublicKey(privateKeyBytes);

    } catch (Exception e) {
      LOGGER.error(null, e);
      return new byte[0];
    }
  }
}
