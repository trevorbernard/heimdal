package io.emax.heimdal.bitcoin.bitcoindrpc;

import java.math.BigInteger;

import io.emax.heimdal.bitcoin.bitcoindrpc.RawTransaction.VariableInt;
import io.emax.heimdal.bitcoin.common.ByteUtilities;

public class RawInput {
  /*
   * https://en.bitcoin.it/wiki/Transaction#general_format_.28inside_a_block.
   * 29_of_each_input_of_a_transaction_-_Txin
   * 
   * general format (inside a block) of each input of a transaction - Txin
   * 
   * Field Description Size
   * 
   * Previous Transaction hash doubled SHA256-hashed of a (previous) to-be-used transaction 32 bytes
   * 
   * Previous Txout-index non negative integer indexing an output of the to-be-used transaction 4
   * bytes
   * 
   * Txin-script length non negative integer VI = VarInt 1 - 9 bytes
   * 
   * Txin-script / scriptSig Script <in-script length>-many bytes
   * 
   * sequence_no normally 0xFFFFFFFF; irrelevant unless transaction's lock_time is > 0 4 bytes
   * 
   */

  private String txHash;
  private int txIndex;
  private long scriptSize = 0;
  private String script = "";
  private int sequence = -1;

  public String getTxHash() {
    return txHash;
  }

  public void setTxHash(String txHash) {
    this.txHash = txHash;
  }

  public int getTxIndex() {
    return txIndex;
  }

  public void setTxIndex(int txIndex) {
    this.txIndex = txIndex;
  }

  public long getScriptSize() {
    return scriptSize;
  }

  public void setScriptSize(long scriptSize) {
    this.scriptSize = scriptSize;
  }

  public String getScript() {
    return script;
  }

  public void setScript(String script) {
    this.script = script;
  }

  public int getSequence() {
    return sequence;
  }

  public void setSequence(int sequence) {
    this.sequence = sequence;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((script == null) ? 0 : script.hashCode());
    result = prime * result + (int) (scriptSize ^ (scriptSize >>> 32));
    result = prime * result + sequence;
    result = prime * result + ((txHash == null) ? 0 : txHash.hashCode());
    result = prime * result + txIndex;
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    RawInput other = (RawInput) obj;
    if (script == null) {
      if (other.script != null)
        return false;
    } else if (!script.equals(other.script))
      return false;
    if (scriptSize != other.scriptSize)
      return false;
    if (sequence != other.sequence)
      return false;
    if (txHash == null) {
      if (other.txHash != null)
        return false;
    } else if (!txHash.equals(other.txHash))
      return false;
    if (txIndex != other.txIndex)
      return false;
    return true;
  }

  @Override
  public String toString() {
    return "RawInput [txHash=" + txHash + ", txIndex=" + txIndex + ", scriptSize=" + scriptSize
        + ", script=" + script + ", sequence=" + sequence + "]";
  }

  public String encode() {
    String tx = "";
    // Tx Hash
    byte[] hashBytes = ByteUtilities.toByteArray(getTxHash());
    hashBytes = ByteUtilities.leftPad(hashBytes, 32, (byte)0x00);
    hashBytes = ByteUtilities.flipEndian(hashBytes);
    tx += ByteUtilities.toHexString(hashBytes);

    // Tx Index
    byte[] indexBytes =
        ByteUtilities.stripLeadingNullBytes(BigInteger.valueOf(getTxIndex()).toByteArray());
    indexBytes = ByteUtilities.leftPad(indexBytes, 4, (byte)0x00);
    indexBytes = ByteUtilities.flipEndian(indexBytes);
    tx += ByteUtilities.toHexString(indexBytes);

    // Script Size
    setScriptSize(getScript().length()/2);
    byte[] scriptSizeBytes = RawTransaction.writeVariableInt(getScriptSize());
    tx += ByteUtilities.toHexString(scriptSizeBytes);

    // Script
    byte[] scriptBytes = ByteUtilities.toByteArray(getScript());
    tx += ByteUtilities.toHexString(scriptBytes);

    // Sequence
    byte[] sequenceBytes =
        ByteUtilities.stripLeadingNullBytes(BigInteger.valueOf(getSequence()).toByteArray());
    sequenceBytes = ByteUtilities.leftPad(sequenceBytes, 4, (byte)0xFF);
    sequenceBytes = ByteUtilities.flipEndian(sequenceBytes);
    tx += ByteUtilities.toHexString(sequenceBytes);

    return tx;
  }

  public static RawInput parse(String txData) {
    RawInput input = new RawInput();
    byte[] rawTx = ByteUtilities.toByteArray(txData);
    int buffPointer = 0;

    byte[] hashBytes = ByteUtilities.readBytes(rawTx, buffPointer, 32);
    buffPointer += 32;
    hashBytes = ByteUtilities.flipEndian(hashBytes);
    input.setTxHash(ByteUtilities.toHexString(hashBytes));

    byte[] indexBytes = ByteUtilities.readBytes(rawTx, buffPointer, 4);
    buffPointer += 4;
    indexBytes = ByteUtilities.flipEndian(indexBytes);
    input.setTxIndex(new BigInteger(1, indexBytes).intValue());

    VariableInt vScriptSize = RawTransaction.readVariableInt(rawTx, buffPointer);
    buffPointer += vScriptSize.getSize();
    input.setScriptSize(vScriptSize.getValue());

    byte[] scriptBytes = ByteUtilities.readBytes(rawTx, buffPointer, (int) input.getScriptSize());
    buffPointer += input.getScriptSize();
    input.setScript(ByteUtilities.toHexString(scriptBytes));

    byte[] sequenceBytes = ByteUtilities.readBytes(rawTx, buffPointer, 4);
    buffPointer += 4;
    sequenceBytes = ByteUtilities.flipEndian(sequenceBytes);
    input.setSequence(new BigInteger(1, sequenceBytes).intValue());

    return input;
  }

  public long getDataSize() {
    int sizeSize = RawTransaction.writeVariableInt(getScriptSize()).length;
    // Tx Hash + Index + scriptSize + Script + sequence
    return 32 + 4 + sizeSize + getScriptSize() + 4;
  }
}
