/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.southernstorm.noise.tests;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;
import javax.xml.bind.DatatypeConverter;

import com.southernstorm.json.JsonReader;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes Noise vector tests in JSON format.
 */
public class VectorTests {

	/**
	 * Information about a handshake or transport message.
	 */
	private static class TestMessage
	{
		public byte[] payload;
		public byte[] ciphertext;
	}

	/**
	 * Information about a Noise test vector that was parsed from a JSON stream.
	 */
	private static class TestVector
	{
		public String name;
		public String pattern;
		public String dh;
		public String hybrid;
		public String cipher;
		public String hash;
		public String fallback_pattern;
		public byte[] init_prologue;
		public byte[] init_ephemeral;
		public byte[] init_hybrid;
		public byte[] init_static;
		public byte[] init_remote_static;
		public byte[] init_psk;
		public byte[] init_ssk;
		public byte[] resp_prologue;
		public byte[] resp_ephemeral;
		public byte[] resp_hybrid;
		public byte[] resp_static;
		public byte[] resp_remote_static;
		public byte[] resp_psk;
		public byte[] resp_ssk;
		public byte[] handshake_hash;
		public boolean failure_expected;
		public boolean fallback_expected;
		public TestMessage[] messages;

		public void addMessage(TestMessage msg)
		{
			TestMessage[] newMessages;
			if (messages != null) {
				newMessages = new TestMessage [messages.length + 1];
				System.arraycopy(messages, 0, newMessages, 0, messages.length);
				newMessages[messages.length] = msg;
			} else {
				newMessages = new TestMessage [1];
				newMessages[0] = msg;
			}
			messages = newMessages;
		}
	}

	private void assertSubArrayEquals(String msg, byte[] expected, byte[] actual)
	{
		for (int index = 0; index < expected.length; ++index)
			assertEquals(expected[index], actual[index], msg + "[" + index + "]");
	}

	@ParameterizedTest
	@MethodSource
	void testVectors(TestVector vec) throws ShortBufferException, BadPaddingException, NoSuchAlgorithmException
	{
		HandshakeState initiator = new HandshakeState(vec.name, HandshakeState.INITIATOR);
		HandshakeState responder = new HandshakeState(vec.name, HandshakeState.RESPONDER);
		assertEquals(HandshakeState.INITIATOR, initiator.getRole());
		assertEquals(HandshakeState.RESPONDER, responder.getRole());
		assertEquals(vec.name, initiator.getProtocolName());
		assertEquals(vec.name, responder.getProtocolName());

		// Set all keys and special values that we need.
		if (vec.init_prologue != null)
			initiator.setPrologue(vec.init_prologue, 0, vec.init_prologue.length);
		if (vec.init_static != null)
			initiator.getLocalKeyPair().setPrivateKey(vec.init_static, 0);
		if (vec.init_remote_static != null)
			initiator.getRemotePublicKey().setPublicKey(vec.init_remote_static, 0);
		if (vec.init_hybrid != null)
			initiator.getFixedHybridKey().setPrivateKey(vec.init_hybrid, 0);
		if (vec.init_ephemeral != null)
			initiator.getFixedEphemeralKey().setPrivateKey(vec.init_ephemeral, 0);
		if (vec.init_psk != null)
			initiator.setPreSharedKey(vec.init_psk, 0, vec.init_psk.length);
		if (vec.resp_prologue != null)
			responder.setPrologue(vec.resp_prologue, 0, vec.resp_prologue.length);
		if (vec.resp_static != null)
			responder.getLocalKeyPair().setPrivateKey(vec.resp_static, 0);
		if (vec.resp_remote_static != null)
			responder.getRemotePublicKey().setPublicKey(vec.resp_remote_static, 0);
		if (vec.resp_ephemeral != null) {
			// Note: The test data contains responder ephemeral keys for one-way
		    // patterns which doesn't actually make sense.  Ignore those keys.
			if (vec.pattern.length() != 1)
				responder.getFixedEphemeralKey().setPrivateKey(vec.resp_ephemeral, 0);
		}
		if (vec.resp_hybrid != null)
			responder.getFixedHybridKey().setPrivateKey(vec.resp_hybrid, 0);
		if (vec.resp_psk != null)
			responder.setPreSharedKey(vec.resp_psk, 0, vec.resp_psk.length);

		// Start both sides of the handshake.
		assertEquals(HandshakeState.NO_ACTION, initiator.getAction());
		assertEquals(HandshakeState.NO_ACTION, responder.getAction());
		initiator.start();
		responder.start();
		assertEquals(HandshakeState.WRITE_MESSAGE, initiator.getAction());
		assertEquals(HandshakeState.READ_MESSAGE, responder.getAction());

		// Work through the messages one by one until both sides "split".
		int role = HandshakeState.INITIATOR;
		int index = 0;
		boolean isOneWay = (vec.pattern.length() == 1);
		boolean fallback = vec.fallback_expected;
		byte[] message = new byte [8192];
		byte[] plaintext = new byte [8192];
		for (; index < vec.messages.length; ++index) {
			final HandshakeState send, recv;

			if (initiator.getAction() == HandshakeState.SPLIT &&
					responder.getAction() == HandshakeState.SPLIT) {
				break;
			}
			if (role == HandshakeState.INITIATOR) {
				// Send on the initiator, receive on the responder.
				send = initiator;
				recv = responder;
				if (!isOneWay)
					role = HandshakeState.RESPONDER;
			} else {
				// Send on the responder, receive on the initiator.
				send = responder;
				recv = initiator;
				role = HandshakeState.INITIATOR;
			}
			assertEquals(HandshakeState.WRITE_MESSAGE, send.getAction());
			assertEquals(HandshakeState.READ_MESSAGE, recv.getAction());
			TestMessage msg = vec.messages[index];
			int len = send.writeMessage(message, 0, msg.payload, 0, msg.payload.length);
			assertEquals(msg.ciphertext.length, len);
			assertSubArrayEquals(index + ": ciphertext", msg.ciphertext, message);
			if (fallback) {
				// Perform a read on the responder, which will fail.
				assertThrows(BadPaddingException.class, () -> recv.readMessage(message, 0, len, plaintext, 0),
						"read should have triggered fallback");

				// Look up the pattern to fall back to.
				String pattern = vec.fallback_pattern;
				if (pattern == null)
					pattern = "XXfallback";

				// Initiate fallback on both sides.
				initiator.fallback(pattern);
				responder.fallback(pattern);

				// Restart the protocols.
				initiator.start();
				responder.start();

				// Only need to fallback once.
				fallback = false;
			} else {
				int plen = recv.readMessage(message, 0, len, plaintext, 0);
				assertEquals(msg.payload.length, plen);
				assertSubArrayEquals(index + ": payload", msg.payload, plaintext);
			}
		}
		if (vec.fallback_expected) {
			// The roles will have reversed during the handshake.
			assertEquals(HandshakeState.RESPONDER, initiator.getRole());
			assertEquals(HandshakeState.INITIATOR, responder.getRole());
		} else {
			assertEquals(HandshakeState.INITIATOR, initiator.getRole());
			assertEquals(HandshakeState.RESPONDER, responder.getRole());
		}

		// Handshake finished.  Check the handshake hash values.
		if (vec.handshake_hash != null) {
			assertArrayEquals(vec.handshake_hash, initiator.getHandshakeHash());
			assertArrayEquals(vec.handshake_hash, responder.getHandshakeHash());
		}

		// Split the two sides to get the transport ciphers.
		CipherStatePair initPair;
		CipherStatePair respPair;
		assertEquals(HandshakeState.SPLIT, initiator.getAction());
		assertEquals(HandshakeState.SPLIT, responder.getAction());
		if (vec.init_ssk != null)
			initPair = initiator.split(vec.init_ssk, 0, vec.init_ssk.length);
		else
			initPair = initiator.split();
		if (vec.resp_ssk != null)
			respPair = responder.split(vec.resp_ssk, 0, vec.resp_ssk.length);
		else
			respPair = responder.split();
		assertEquals(HandshakeState.COMPLETE, initiator.getAction());
		assertEquals(HandshakeState.COMPLETE, responder.getAction());

		// Now handle the data transport.
		CipherState csend, crecv;
		for (; index < vec.messages.length; ++index) {
			TestMessage msg = vec.messages[index];
			if (role == HandshakeState.INITIATOR) {
				// Send on the initiator, receive on the responder.
				csend = initPair.getSender();
				crecv = respPair.getReceiver();
				if (!isOneWay)
					role = HandshakeState.RESPONDER;
			} else {
				// Send on the responder, receive on the initiator.
				csend = respPair.getSender();
				crecv = initPair.getReceiver();
				role = HandshakeState.INITIATOR;
			}
			int len = csend.encryptWithAd(null, msg.payload, 0, message, 0, msg.payload.length);
			assertEquals(msg.ciphertext.length, len);
			assertSubArrayEquals(index + ": ciphertext", msg.ciphertext, message);
			int plen = crecv.decryptWithAd(null, message, 0, plaintext, 0, len);
			assertEquals(msg.payload.length, plen);
			assertSubArrayEquals(index + ": payload", msg.payload, plaintext);
		}

		// Clean up.
		initiator.destroy();
		responder.destroy();
		initPair.destroy();
		respPair.destroy();
	}

	private static Stream<Arguments> testVectors() throws IOException {
		try (InputStream testVectorInputStream = VectorTests.class.getResourceAsStream("test-vectors.json")) {
			if (testVectorInputStream == null) {
				throw new IOException("Could not load test vectors");
			}

			return loadTestVectors(testVectorInputStream).stream()
					.map(testVector -> Arguments.of(Named.of(testVector.name, testVector)));
		}
	}

	private static TestVector getNextVector(final JsonReader reader) throws IOException
	{
    	boolean res = true;
		// Parse the contents of the test vector.
		TestVector vec = new TestVector();
		while (reader.hasNext()) {
			String name = reader.nextName();
			if (name.equals("name"))
				vec.name = reader.nextString();
			else if (name.equals("pattern"))
				vec.pattern = reader.nextString();
			else if (name.equals("dh"))
				vec.dh = reader.nextString();
			else if (name.equals("hybrid"))
				vec.hybrid = reader.nextString();
			else if (name.equals("cipher"))
				vec.cipher = reader.nextString();
			else if (name.equals("hash"))
				vec.hash = reader.nextString();
			else if (name.equals("fallback_pattern"))
				vec.fallback_pattern = reader.nextString();
			else if (name.equals("init_prologue"))
				vec.init_prologue = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_ephemeral"))
				vec.init_ephemeral = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_hybrid_ephemeral"))
				vec.init_hybrid = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_static"))
				vec.init_static = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_remote_static"))
				vec.init_remote_static = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_psk"))
				vec.init_psk = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("init_ssk"))
				vec.init_ssk = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_prologue"))
				vec.resp_prologue = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_ephemeral"))
				vec.resp_ephemeral = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_hybrid_ephemeral"))
				vec.resp_hybrid = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_static"))
				vec.resp_static = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_remote_static"))
				vec.resp_remote_static = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_psk"))
				vec.resp_psk = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("resp_ssk"))
				vec.resp_ssk = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("handshake_hash"))
				vec.handshake_hash = DatatypeConverter.parseHexBinary(reader.nextString());
			else if (name.equals("fail"))
				vec.failure_expected = reader.nextBoolean();
			else if (name.equals("fallback"))
				vec.fallback_expected = reader.nextBoolean();
			else if (name.equals("messages")) {
				reader.beginArray();
				while (reader.hasNext()) {
					TestMessage msg = new TestMessage();
					reader.beginObject();
					while (reader.hasNext()) {
						name = reader.nextName();
						if (name.equals("payload"))
							msg.payload = DatatypeConverter.parseHexBinary(reader.nextString());
						else if (name.equals("ciphertext"))
							msg.ciphertext = DatatypeConverter.parseHexBinary(reader.nextString());
						else
							reader.skipValue();
					}
					vec.addMessage(msg);
					reader.endObject();
				}
				reader.endArray();
			} else {
				reader.skipValue();
			}
		}

		// Format the complete protocol name.
		String protocolName = "Noise";
		if (vec.init_psk != null || vec.resp_psk != null)
			protocolName += "PSK";
		String dh = vec.dh;
		if (vec.hybrid != null)
			dh = dh + "+" + vec.hybrid;
		protocolName += "_" + vec.pattern + "_" + dh + "_" + vec.cipher + "_" + vec.hash;
		if (vec.name == null)
			vec.name = protocolName;

		return vec;
	}

  private static List<TestVector> loadTestVectors(InputStream jsonInputStream) throws IOException {
	List<TestVector> testVectors = new ArrayList<>();

	try (JsonReader jsonReader = new JsonReader(new BufferedReader(new InputStreamReader(jsonInputStream)))) {
		jsonReader.beginObject();
		while (jsonReader.hasNext()) {
			String name = jsonReader.nextName();
			if (name.equals("vectors")) {
				jsonReader.beginArray();
				while (jsonReader.hasNext()) {
					jsonReader.beginObject();
					testVectors.add(getNextVector(jsonReader));
					jsonReader.endObject();
				}
				jsonReader.endArray();
			} else {
				jsonReader.skipValue();
			}
		}
		jsonReader.endObject();
	}

	return testVectors;
  }
}
