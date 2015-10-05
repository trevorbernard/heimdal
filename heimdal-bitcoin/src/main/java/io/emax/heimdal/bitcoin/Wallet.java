package io.emax.heimdal.bitcoin;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.emax.heimdal.bitcoin.bitcoindrpc.BitcoindRpc;
import io.emax.heimdal.bitcoin.bitcoindrpc.DecodedTransaction;
import io.emax.heimdal.bitcoin.bitcoindrpc.DecodedTransaction.DecodedInput;
import io.emax.heimdal.bitcoin.bitcoindrpc.MultiSig;
import io.emax.heimdal.bitcoin.bitcoindrpc.Outpoint;
import io.emax.heimdal.bitcoin.bitcoindrpc.OutpointDetails;
import io.emax.heimdal.bitcoin.bitcoindrpc.Output;
import io.emax.heimdal.bitcoin.bitcoindrpc.RawInput;
import io.emax.heimdal.bitcoin.bitcoindrpc.RawOutput;
import io.emax.heimdal.bitcoin.bitcoindrpc.RawTransaction;
import io.emax.heimdal.bitcoin.bitcoindrpc.SigHash;
import io.emax.heimdal.bitcoin.bitcoindrpc.SignedTransaction;
import io.emax.heimdal.bitcoin.common.ByteUtilities;
import io.emax.heimdal.bitcoin.common.DeterministicTools;

public class Wallet implements io.emax.heimdal.api.currency.Wallet {

  private CurrencyConfiguration config = new CurrencyConfiguration();
  private BitcoindRpc bitcoindRpc = BitcoindResource.getResource().getBitcoindRpc();

  private static HashMap<String, String> multiSigRedeemScripts = new HashMap<>();

  public Wallet(BitcoindRpc rpc) {
    this.bitcoindRpc = rpc;
  }

  public Wallet() {

  }

  @Override
  public String createAddress(String name) {
    int rounds = 1;
    String privateKey =
        DeterministicTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
    String newAddress = DeterministicTools.getPublicAddress(privateKey);
    // Hash the user's key so it's not stored in the wallet
    String internalName = "Single-" + DeterministicTools.encodeUserKey(name);

    String[] existingAddresses = bitcoindRpc.getaddressesbyaccount(internalName);
    boolean oldAddress = true;

    while (oldAddress && rounds <= config.getMaxDeterministicAddresses()) {
      oldAddress = false;
      for (int i = 0; i < existingAddresses.length; i++) {
        if (existingAddresses[i].equalsIgnoreCase(newAddress)) {
          oldAddress = true;
          rounds++;
          privateKey = DeterministicTools.getDeterministicPrivateKey(name,
              config.getServerPrivateKey(), rounds);
          newAddress = DeterministicTools.getPublicAddress(privateKey);
          break;
        }
      }
    }
    bitcoindRpc.importaddress(newAddress, internalName, false);

    return newAddress;
  }

  @Override
  public Iterable<String> getAddresses(String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = DeterministicTools.encodeUserKey(name);

    String[] addresses = bitcoindRpc.getaddressesbyaccount(internalName);
    return Arrays.asList(addresses);
  }

  @Override
  public String getMultiSigAddress(Iterable<String> addresses, String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = DeterministicTools.encodeUserKey(name);
    String newAddress = generateMultiSigAddress(addresses, name);
    bitcoindRpc.importaddress(newAddress, internalName, false);

    return newAddress;
  }

  public String generateMultiSigAddress(Iterable<String> addresses, String name) {
    LinkedList<String> multisigAddresses = new LinkedList<>();
    addresses.forEach((address) -> {
      // Check if any of the addresses belong to the user
      int rounds = 1;
      String userPrivateKey =
          DeterministicTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
      String userAddress = DeterministicTools.getPublicAddress(userPrivateKey);

      while (!address.equalsIgnoreCase(userAddress)
          && rounds <= config.getMaxDeterministicAddresses()) {
        rounds++;
        userPrivateKey = DeterministicTools.getDeterministicPrivateKey(name,
            config.getServerPrivateKey(), rounds);
        userAddress = DeterministicTools.getPublicAddress(userPrivateKey);
      }

      // TODO Remove this, debugging for signing code
      String pubKey = DeterministicTools.getPublicKey(userPrivateKey);
      System.out.println("==[DEBUG]==");
      System.out.println("Private Key: " + userPrivateKey);
      System.out.println("Public Key: " + pubKey);
      System.out.println("==[DEBUG]==");

      if (address.equalsIgnoreCase(userAddress)) {
        multisigAddresses.add(DeterministicTools.getPublicKey(userPrivateKey));
      } else {
        multisigAddresses.add(address);
      }
    });

    for (String account : config.getMultiSigAccounts()) {
      if (!account.isEmpty()) {
        multisigAddresses.add(account);
      }
    }

    String[] addressArray = new String[multisigAddresses.size()];
    MultiSig newAddress = bitcoindRpc.createmultisig(config.getMinSignatures(),
        multisigAddresses.toArray(addressArray));

    multiSigRedeemScripts.put(newAddress.getAddress(), newAddress.getRedeemScript());

    return newAddress.getAddress();
  }

  @Override
  public String getBalance(String address) {
    BigDecimal balance = BigDecimal.ZERO;
    Output[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
        config.getMaxConfirmations(), new String[] {address});
    for (Output output : outputs) {
      balance = balance.add(output.getAmount());
    }
    return balance.toPlainString();
  }

  @Override
  public String createTransaction(Iterable<String> fromAddress, Iterable<Recipient> toAddress) {
    List<String> fromAddresses = new LinkedList<>();
    fromAddress.forEach(fromAddresses::add);
    String[] addresses = new String[fromAddresses.size()];
    Outpoint[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
        config.getMaxConfirmations(), fromAddresses.toArray(addresses));

    List<Outpoint> usedOutputs = new LinkedList<>();
    Map<String, BigDecimal> txnOutput = new HashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal subTotal = BigDecimal.ZERO;
    Iterator<Recipient> recipients = toAddress.iterator();
    Recipient recipient = recipients.next();
    boolean filledAllOutputs = false;
    for (Outpoint output : outputs) {
      total = total.add(output.getAmount());
      subTotal = subTotal.add(output.getAmount());
      usedOutputs.add(output);

      if (subTotal.compareTo(recipient.getAmount()) > 0) {
        txnOutput.put(recipient.getRecipientAddress(), recipient.getAmount());
        subTotal = subTotal.subtract(recipient.getAmount());
        if (recipients.hasNext()) {
          recipient = recipients.next();
        } else {
          // TODO don't hardcode fees -- 0.0001 BTC * KB suggested by spec
          txnOutput.put(fromAddress.iterator().next(), subTotal.subtract(new BigDecimal("0.002")));
          filledAllOutputs = true;
        }
        break;
      }
    }

    // We don't have enough to complete the transaction
    if (!filledAllOutputs) {
      return null;
    }

    RawTransaction rawTx = new RawTransaction();
    rawTx.setVersion(1);
    rawTx.setInputCount(usedOutputs.size());
    usedOutputs.forEach((input) -> {
      RawInput rawInput = new RawInput();
      rawInput.setTxHash(input.getTransactionId());
      rawInput.setTxIndex((int) input.getOutputIndex());
      rawInput.setSequence(-1);
      rawTx.getInputs().add(rawInput);
    });
    rawTx.setOutputCount(txnOutput.size());
    txnOutput.forEach((address, amount) -> {
      RawOutput rawOutput = new RawOutput();
      rawOutput.setAmount(amount.multiply(BigDecimal.valueOf(100000000)).longValue());
      String decodedAddress = DeterministicTools.decodeAddressTo160(address);
      byte[] addressBytes = ByteUtilities.toByteArray(decodedAddress);
      String scriptData = "76a914";
      scriptData += ByteUtilities.toHexString(addressBytes);
      scriptData += "88ac";
      rawOutput.setScript(scriptData);
      rawTx.getOutputs().add(rawOutput);
    });
    rawTx.setLockTime(0);

    return rawTx.encode();
  }

  @Override
  public String signTransaction(String transaction, String address) {
    return signTransaction(transaction, address, null);
  }

  @Override
  public String signTransaction(String transaction, String address, String name) {
    int rounds = 1;
    String privateKey = "";
    String userAddress = "";
    SignedTransaction signedTransaction = null;

    if (name != null) {
      privateKey =
          DeterministicTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
      userAddress = DeterministicTools.getPublicAddress(privateKey);
      while (!generateMultiSigAddress(Arrays.asList(new String[] {userAddress}), name)
          .equalsIgnoreCase(address) && !userAddress.equalsIgnoreCase(address)
          && rounds < config.getMaxDeterministicAddresses()) {
        rounds++;
        privateKey = DeterministicTools.getDeterministicPrivateKey(name,
            config.getServerPrivateKey(), rounds);
        userAddress = DeterministicTools.getPublicAddress(privateKey);
      }

      // If we hit max addresses/user bail out
      if (!generateMultiSigAddress(Arrays.asList(new String[] {userAddress}), name)
          .equalsIgnoreCase(address) && !userAddress.equalsIgnoreCase(address)) {
        return transaction;
      }

      // TODO This will likely be removed for the new lookup below.
      // We have the private key, now get all the unspent inputs so we have the redeemScripts.
      DecodedTransaction myTx = bitcoindRpc.decoderawtransaction(transaction);
      List<DecodedInput> inputs = myTx.getInputs();

      Outpoint[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
          config.getMaxConfirmations(), new String[] {});
      List<OutpointDetails> myOutpoints = new LinkedList<>();

      inputs.forEach((input) -> {
        for (Outpoint output : outputs) {
          if (output.getTransactionId().equalsIgnoreCase(input.getTransactionId())
              && output.getOutputIndex() == input.getOutputIndex()) {
            OutpointDetails outpoint = new OutpointDetails();
            outpoint.setTransactionId(output.getTransactionId());
            outpoint.setOutputIndex(output.getOutputIndex());
            outpoint.setScriptPubKey(output.getScriptPubKey());
            outpoint.setRedeemScript(multiSigRedeemScripts.get(output.getAddress()));
            myOutpoints.add(outpoint);
          }
        }
      });

      OutpointDetails[] outpointArray = new OutpointDetails[myOutpoints.size()];
      outpointArray = myOutpoints.toArray(outpointArray);

      // TODO - We should know how to parse and sign this, not be asking the daemon to.
      // Opens up the possibility of a custom signer
      // TODO - Pull up the original script
      // TODO Temporary lookup for testing but it will likely replace the above.

      // TODO - Sign the data, get the pub key, encode it

      signedTransaction = bitcoindRpc.signrawtransaction(transaction, outpointArray,
          new String[] {privateKey}, SigHash.ALL);
    } else {
      // If we're not restricting the keystore, bitcoind knows about the redeem script
      signedTransaction =
          bitcoindRpc.signrawtransaction(transaction, new OutpointDetails[] {}, null, SigHash.ALL);
    }

    return signedTransaction.getTransaction();
  }

  @Override
  public String sendTransaction(String transaction) {
    return bitcoindRpc.sendrawtransaction(transaction, false);
  }
}
