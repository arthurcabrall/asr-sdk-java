/*******************************************************************************
 * Copyright 2017 CPqD. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package br.com.cpqd.asr.recognizer.ws;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.EncodeException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslContextConfigurator;
import org.glassfish.tyrus.client.SslEngineConfigurator;
import org.glassfish.tyrus.client.ThreadPoolConfig;
import org.glassfish.tyrus.client.auth.AuthConfig;
import org.glassfish.tyrus.client.auth.Credentials;
import org.glassfish.tyrus.container.grizzly.client.GrizzlyClientProperties;
import org.glassfish.tyrus.core.TyrusWebSocketEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.cpqd.asr.protocol.AsrMessage;
import br.com.cpqd.asr.protocol.EndOfSpeechMessage;
import br.com.cpqd.asr.protocol.RecognitionResult;
import br.com.cpqd.asr.protocol.RecognitionResultMessage;
import br.com.cpqd.asr.protocol.ResponseMessage;
import br.com.cpqd.asr.protocol.Sentence;
import br.com.cpqd.asr.protocol.SessionStatus;
import br.com.cpqd.asr.protocol.StartOfSpeechMessage;
import br.com.cpqd.asr.protocol.AsrMessage.AsrMessageType;
import br.com.cpqd.asr.protocol.ResponseMessage.Result;
import br.com.cpqd.asr.protocol.encoder.AsrProtocolEncoder;
import br.com.cpqd.asr.recognizer.RecognitionListener;
import br.com.cpqd.asr.recognizer.config.Config;
import br.com.cpqd.asr.recognizer.model.Interpretation;
import br.com.cpqd.asr.recognizer.model.PartialRecognitionResult;
import br.com.cpqd.asr.recognizer.model.RecognitionAlternative;
import br.com.cpqd.asr.recognizer.model.RecognitionError;
import br.com.cpqd.asr.recognizer.model.RecognitionErrorCode;
import br.com.cpqd.asr.recognizer.model.RecognitionResultCode;

/**
 * Websocket endpoint (JSR 356) for communicating with the ASR server.
 * 
 */
@ClientEndpoint(decoders = { AsrProtocolEncoder.class }, encoders = { AsrProtocolEncoder.class })
public class AsrClientEndpoint {

	private static Logger logger = LoggerFactory.getLogger(AsrClientEndpoint.class.getName());

	/** JSON Mapper. */
	private static final ObjectMapper jsonMapper = new ObjectMapper()
			.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

	private URI uri;
	
	private ClientManager clientManager;

	private Session session;

	private String[] credentials;

	private List<RecognitionListener> listeners = new ArrayList<>();

	private SessionStatus status;

	private BlockingQueue<AsrMessage> responseQueue = new ArrayBlockingQueue<AsrMessage>(1);
	
	private int sessionTimeoutTime = -1;
	
	private boolean closeCalled;

	/**
	 * Constructor.
	 * 
	 * @param url
	 *            the websocket server endpoint URL.
	 * @throws URISyntaxException
	 *             invalid server URL (e.g:
	 *             'ws://127.0.0.1:8025/asr-server/asr').
	 */
	public AsrClientEndpoint(String url) throws URISyntaxException {
		clientManager = ClientManager.createClient();

		clientManager.getProperties().put(GrizzlyClientProperties.SELECTOR_THREAD_POOL_CONFIG,
				ThreadPoolConfig.defaultConfig().setMaxPoolSize(Config.getSelectorThreads()));
		clientManager.getProperties().put(GrizzlyClientProperties.WORKER_THREAD_POOL_CONFIG,
				ThreadPoolConfig.defaultConfig().setMaxPoolSize(Config.getWorkerThreads()));
		clientManager.getProperties().put(TyrusWebSocketEngine.INCOMING_BUFFER_SIZE, Config.getIncomingBufferSize());

		uri = new URI(url);
		
		if (url.toLowerCase().startsWith("wss")) {

			System.getProperties().put("javax.net.debug", Config.getDebugLevel());

			if (Config.getKeystore() != null) {
				System.getProperties().put(SslContextConfigurator.KEY_STORE_FILE, Config.getKeystore());
				System.getProperties().put(SslContextConfigurator.KEY_STORE_PASSWORD, Config.getKeystorePasswd());
			}

			if (Config.getTruststore() != null) {
				System.getProperties().put(SslContextConfigurator.TRUST_STORE_FILE, Config.getTruststore());
				System.getProperties().put(SslContextConfigurator.TRUST_STORE_PASSWORD, Config.getTruststorePasswd());
			}

			SslContextConfigurator defaultConfig = new SslContextConfigurator();
			defaultConfig.retrieve(System.getProperties());

			boolean clientMode = true;
			boolean needClientAuth = false;
			boolean wantClientAuth = false;
			SslEngineConfigurator sslEngineConfigurator = new SslEngineConfigurator(defaultConfig, clientMode,
					needClientAuth, wantClientAuth);
			sslEngineConfigurator.setHostVerificationEnabled(false);
			clientManager.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngineConfigurator);
		}

		if (credentials != null) {
			AuthConfig authConfig = AuthConfig.Builder.create().build();
			clientManager.getProperties().put(ClientProperties.AUTH_CONFIG, authConfig);
			clientManager.getProperties().put(ClientProperties.CREDENTIALS,
					new Credentials(credentials[0], credentials[1].getBytes()));
		}
	}

	/**
	 * Opens a websocket connection with the server.
	 * 
	 * @throws IOException
	 *             some sort of I/O exception has ocurred.
	 * @throws DeploymentException
	 *             error when starting the endpoint.
	 */
	public void open() throws DeploymentException, IOException {
		this.session = clientManager.connectToServer(this, uri);
		this.session.setMaxIdleTimeout(sessionTimeoutTime);
	}

	/**
	 * Returns the registered listeners for protocol events.
	 * 
	 * @return the registered listeners.
	 */
	public List<RecognitionListener> getListeners() {
		return this.listeners;
	}

	/**
	 * Verifies if the server connection is open.
	 * 
	 * @return true if the connection is open.
	 */
	public boolean isOpen() {
		if (session != null && session.isOpen()) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Closes the connection.
	 */
	public void close() {
		try {
			if (isOpen()) {
				closeCalled = true;
				session.close(new CloseReason(CloseCodes.NORMAL_CLOSURE, "Close called"));
			}
		} catch (IOException e) {
			logger.error("Error while closing session: " + e.getMessage());
		}
	}

	/**
	 * Send a binary message to the server, via websocket connection, and wait
	 * for the server response.
	 * 
	 * @param message
	 *            the protocol message object.
	 * @return the response message.
	 * @throws IOException
	 *             if some sort or I/O error has ocurred.
	 * @throws EncodeException
	 *             error when encoding the ASR message to binary message.
	 */
	public ResponseMessage sendMessageAndWait(AsrMessage message) throws IOException, EncodeException {
		session.getBasicRemote().sendObject(message);
		try {
			ResponseMessage response = (ResponseMessage) responseQueue.poll(Config.getExecutorTimeout(),
					TimeUnit.SECONDS);
			return response;
		} catch (InterruptedException e) {
			return null;
		}
	}

	/**
	 * Send a binary message to the server, via websocket connection.
	 * 
	 * @param message
	 *            the protocol message object.
	 * @throws IOException
	 *             if some sort or I/O error has ocurred.
	 * @throws EncodeException
	 *             error when encoding the ASR message to binary message.
	 */
	public void sendMessage(AsrMessage message) throws IOException, EncodeException {
		session.getBasicRemote().sendObject(message);
	}

	/**
	 * Call back method called when the websocket connection is opened.
	 * 
	 * @param session
	 *            the websocket session.
	 */
	@OnOpen
	public void onOpen(Session session) {
		logger.trace("[{}] Connection opened", session.getId());
		this.session = session;
	}

	/**
	 * Call back method called when the websocket connection is closed.
	 * 
	 * @param session
	 *            the websocket session.
	 * @param closeReason
	 *            the close reason defined by the server.
	 */
	@OnClose
	public void onClose(Session session, CloseReason closeReason) {
		logger.trace("Connection closed because of {}", closeReason);
		// notificar os listeners caso a sessao tenha sido encerrada
		// abrutamente: encerrar reconhecimentos em andamento
		if (!closeCalled) {
			String closeStr = closeReason.getCloseCode()
					+ (closeReason.getReasonPhrase().length() > 0 ? " - " + closeReason.getReasonPhrase() : "");

			RecognitionErrorCode code = null;
			if (closeReason.getReasonPhrase() != null && closeReason.getReasonPhrase().contains("timeout.")) {
				code = RecognitionErrorCode.SESSION_TIMEOUT;
			} else {
				code = RecognitionErrorCode.CONNECTION_FAILURE;
			}

			for (RecognitionListener listener : this.listeners) {
				listener.onError(new RecognitionError(code, closeStr));
			}

		}
	}

	/**
	 * Call back method called when a protocol message is received.
	 * 
	 * @param message
	 *            the protocol message.
	 * @param session
	 *            the websocket session.
	 */
	@OnMessage
	public void onMessage(AsrMessage message, Session session) {

		if (message instanceof RecognitionResultMessage) {
			RecognitionResultMessage recogResult = (RecognitionResultMessage) message;
			// atualiza status da sessao
			status = recogResult.getSessionStatus();

			for (RecognitionListener listener : listeners) {
				if (recogResult.isPartial()) {
					// notifica reconhecimento parcial
					try {
						if (recogResult.getRecognitionResult() != null
								&& !recogResult.getRecognitionResult().getAlternatives().isEmpty()) {
							String text = recogResult.getRecognitionResult().getAlternative(0).getText();
							PartialRecognitionResult partialResult = new PartialRecognitionResult();
							partialResult.setSpeechSegmentIndex(0);
							partialResult.setText(text);
							listener.onPartialRecognitionResult(partialResult);
						}
					} catch (Exception e) {
						logger.warn("Error notifying listeners of partial result", e);
					}

				} else {
					// notifica resultado final do reconhecimento (RECOGNIZED ou
					// NO_MATCH, NO_INPUT_TIMEOUT, MAX_SPEECH, NO_SPEECH, EARLY_SPEECH, RECOGNITION_TIMEOUT
					// FAILURE) 
					RecognitionResult result = recogResult.getRecognitionResult();

					br.com.cpqd.asr.recognizer.model.RecognitionResult aResult = new br.com.cpqd.asr.recognizer.model.RecognitionResult();
					aResult.setResultCode(RecognitionResultCode.valueOf(result.getRecognitionStatus().toString()));
					for (Sentence s : result.getAlternatives()) {
						RecognitionAlternative alt = new RecognitionAlternative();
						alt.setText(s.getText());
						alt.setConfidence(s.getScore());
						for (Object interpObj : s.getInterpretations()) {
							Interpretation interp = new Interpretation();
							try {
								interp.setInterpretation(jsonMapper.writeValueAsString(interpObj));
								interp.setInterpretationConfidence(0);
								alt.getInterpretations().add(interp);
							} catch (Exception e) {
								logger.error("Error serializing intepretation obj to JSON [{}]: {}", e.getMessage(),
										interpObj.toString());
							}
						}

						// TODO for future implementation
						// alt.setLanguageModel(languageModel);
						// alt.setWordAlignment(wordAlignment);
						// alt.setWordConficence(wordConficence);

						aResult.getAlternatives().add(alt);
					}
					listener.onRecognitionResult(aResult);

				}
			}
			return;

		} else if (message instanceof ResponseMessage) {
			ResponseMessage resp = (ResponseMessage) message;
			// atualiza status da sessao
			status = resp.getSessionStatus();

			if (resp.getResult().equals(Result.SUCCESS)) {
				// notifica o evento de LISTENING
				if (resp.getMethod().equals(AsrMessageType.START_RECOGNITION)
						&& resp.getSessionStatus().equals(SessionStatus.LISTENING)) {
					for (RecognitionListener listener : listeners) {
						listener.onListening();
					}
				}
			} else if (resp.getMethod().equals(AsrMessageType.SEND_AUDIO)) {
				// evita de inserir resposta de erro na fila de mensagens (pode
				// prejudicar comunicacao do cliente com servidor)
				logger.warn("[{}] Audio packet rejected by server", resp.getHandle());
				return;
			}

		} else if (message instanceof StartOfSpeechMessage) {
			for (RecognitionListener listener : listeners) {
				int time = 0; // TODO for future implementation
				listener.onSpeechStart(time);
			}
			return;

		} else if (message instanceof EndOfSpeechMessage) {
			// atualiza status da sessao
			status = ((EndOfSpeechMessage) message).getSessionStatus();
			for (RecognitionListener listener : listeners) {
				int time = 0; // TODO for future implementation
				listener.onSpeechStop(time);
			}
			return;
		}

		// ao receber a mensagem do servidor, adiciona na fila
		try {
			if (!responseQueue.offer(message)) {
				logger.warn("Messsage discarded. Result queue is full. {}", message.toString());
			}
		} catch (Exception e) {
			logger.error("Error putting message in queue: {}", message.toString(), e);
		}
	}

	@OnError
	public void onError(Session session, Throwable thr) {
		if (!session.isOpen()) {
			logger.warn("Socket closed because of {}", thr.getMessage());
		} else {
			logger.warn("Unexpected error", thr);
		}

	}
	
	/**
	 * Returns the websocket session id.
	 * 
	 * @return the websocket session id.
	 */
	public String getSessionId() {
		if (isOpen()) {
			return session.getId();
		}

		return null;
	}

	/**
	 * Returns the recognition session status.
	 * 
	 * @return the recognition session status.
	 */
	public SessionStatus getStatus() {
		return status;
	}

	/**
	 * @param credentials
	 *            the credentials to set
	 */
	public void setCredentials(String[] credentials) {
		this.credentials = credentials;
	}

	/**
	 * @return the sessionTimeoutTime
	 */
	public int getSessionTimeoutTime() {
		return sessionTimeoutTime;
	}

	/**
	 * @param sessionTimeoutTime the sessionTimeoutTime to set
	 */
	public void setSessionTimeoutTime(int sessionTimeoutTime) {
		this.sessionTimeoutTime = sessionTimeoutTime;
	}

}
