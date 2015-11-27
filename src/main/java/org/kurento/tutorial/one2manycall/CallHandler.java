/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */
package org.kurento.tutorial.one2manycall;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.MediaPipeline;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.KurentoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Protocol handler for 1 to N video call communication.
 * 
 * @author Boni Garcia (bgarcia@gsyc.es)
 * @since 5.0.0
 */
public class CallHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory
			.getLogger(CallHandler.class);
	private static final Gson gson = new GsonBuilder().create();

	private ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<String, UserSession>();

	@Autowired
	private KurentoClient kurento;

	private String[] cameraUris = {
		"rtsp://admin:admindk@192.168.1.6:554",
		"rtsp://admin:admin@192.168.1.99:554",
	};
	
	private MediaPipeline pipeline;
	private UserSession masterUserSession;
	private PlayerEndpoint[] cameraEndpoints;
	
	
	@Override
	public void handleTextMessage(WebSocketSession session, TextMessage message)
			throws Exception {
		JsonObject jsonMessage = gson.fromJson(message.getPayload(),
				JsonObject.class);
		log.debug("Incoming message from session '{}': {}", session.getId(),
				jsonMessage);

		switch (jsonMessage.get("id").getAsString()) {
		case "viewer":
			try {
				viewer(session, jsonMessage);
			} catch (Throwable t) {
				stop(session);
				log.error(t.getMessage(), t);
				JsonObject response = new JsonObject();
				response.addProperty("id", "viewerResponse");
				response.addProperty("response", "rejected");
				response.addProperty("message", t.getMessage());
				session.sendMessage(new TextMessage(response.toString()));
			}
			break;
		case "stop":
			stop(session);
			break;
		default:
			break;
		}
	}
	
	private void initEverything() {
		pipeline = kurento.createMediaPipeline();
		cameraEndpoints = new PlayerEndpoint[cameraUris.length];
		int i;
		for (i=0; i<cameraUris.length; i++) {
			cameraEndpoints[i] = new PlayerEndpoint.Builder(pipeline, cameraUris[i]).build();
			cameraEndpoints[i].play();
		}
	}

	private synchronized void viewer(WebSocketSession session,
		JsonObject jsonMessage) throws IOException {
		
		// it would be better to make pipeline and init cameras somewhere else
		// and just pass them into this class
		if (pipeline == null)
			initEverything();
		
		if (viewers.containsKey(session.getId())) {
			JsonObject response = new JsonObject();
			response.addProperty("id", "viewerResponse");
			response.addProperty("response", "rejected");
			response.addProperty(
					"message",
					"You are already viewing in this session. Use a different browser to add additional viewers.");
			session.sendMessage(new TextMessage(response.toString()));
			return;
		}
		UserSession viewer = new UserSession(session);
		viewers.put(session.getId(), viewer);

		String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer")
				.getAsString();

		WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline)
				.build();
		viewer.setWebRtcEndpoint(nextWebRtc);
		
		int cameraId = jsonMessage.getAsJsonPrimitive("cameraId").getAsInt();
		cameraEndpoints[cameraId].connect(nextWebRtc);
		String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

		JsonObject response = new JsonObject();
		response.addProperty("id", "viewerResponse");
		response.addProperty("response", "accepted");
		response.addProperty("sdpAnswer", sdpAnswer);
		viewer.sendMessage(response);
	}

	private synchronized void stop(WebSocketSession session) throws IOException {
		String sessionId = session.getId();
		if (viewers.containsKey(sessionId)) {
			if (viewers.get(sessionId).getWebRtcEndpoint() != null) {
				viewers.get(sessionId).getWebRtcEndpoint().release();
			}
			viewers.remove(sessionId);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session,
			CloseStatus status) throws Exception {
		stop(session);
	}

}
