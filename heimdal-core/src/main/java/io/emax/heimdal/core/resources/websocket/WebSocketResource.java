package io.emax.heimdal.core.resources.websocket;

import java.io.IOException;

import javax.ws.rs.Path;

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.handler.OnMessage;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.BroadcastOnPostAtmosphereInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.emax.heimdal.core.currency.Common;

@Path("/")
@AtmosphereHandlerService(path = "/ws", broadcasterCache = UUIDBroadcasterCache.class,
    interceptors = {AtmosphereResourceLifecycleInterceptor.class,
        BroadcastOnPostAtmosphereInterceptor.class, TrackMessageSizeInterceptor.class,
        HeartbeatInterceptor.class})
public class WebSocketResource extends OnMessage<String> {
  Logger logger = LoggerFactory.getLogger(WebSocketResource.class);

  @Override
  public void onMessage(AtmosphereResponse arg0, String arg1) throws IOException {
    String functionCall = arg0.request().getRequestURI().replaceAll("/ws", "");
    switch (functionCall.toLowerCase()) {
      case "/getcurrencies":
        logger.debug("[GetCurrencies:WSRequest]");
        arg0.write(Common.getCurrencies());
        break;
      case "/getnewaccount":
        logger.debug("[GetNewAccount:WSRequest]");
        arg0.write(Common.getNewAccount(arg1));
        break;
      case "/listallaccounts":
        logger.debug("[ListAllAccounts:WSRequest]");
        arg0.write(Common.listAllAccounts(arg1));
        break;
      case "/getbalance":
        logger.debug("[GetBalance:WSRequest]");
        arg0.write(Common.getBalance(arg1));
        break;
      case "/monitorbalance":
        logger.debug("[MonitorBalance:WSRequest]");
        arg0.write(Common.monitorBalance(arg1, arg0));
        break;
      case "/preparetransaction":
        logger.debug("[PrepareTransaction:WSRequest]");
        arg0.write(Common.prepareTransaction(arg1));
        break;
      case "/approvetransaction":
        logger.debug("[ApproveTransaction:WSRequest]");
        arg0.write(Common.approveTransaction(arg1, true));
        break;
      case "/submittransaction":
        logger.debug("[SubmitTransaction:WSRequest]");
        arg0.write(Common.submitTransaction(arg1));
        break;
      default:
        logger.warn("[Invalid:WSRequest] " + arg1);
        arg0.write("No such function");
        break;
    }
  }
}
