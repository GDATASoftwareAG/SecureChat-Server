package org.whispersystems.textsecuregcm.tests.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.sun.jersey.api.client.ClientResponse;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.whispersystems.textsecuregcm.controllers.MessageController;
import org.whispersystems.textsecuregcm.entities.IncomingMessageList;
import org.whispersystems.textsecuregcm.entities.MessageProtos;
import org.whispersystems.textsecuregcm.entities.MismatchedDevices;
import org.whispersystems.textsecuregcm.entities.OutgoingMessageEntity;
import org.whispersystems.textsecuregcm.entities.OutgoingMessageEntityList;
import org.whispersystems.textsecuregcm.entities.SignedPreKey;
import org.whispersystems.textsecuregcm.entities.StaleDevices;
import org.whispersystems.textsecuregcm.federation.FederatedClientManager;
import org.whispersystems.textsecuregcm.limits.RateLimiter;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.push.PushSender;
import org.whispersystems.textsecuregcm.push.ReceiptSender;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;
import org.whispersystems.textsecuregcm.util.SystemMapper;

import javax.ws.rs.core.MediaType;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.dropwizard.testing.junit.ResourceTestRule;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;
import static org.whispersystems.textsecuregcm.tests.util.JsonHelpers.asJson;
import static org.whispersystems.textsecuregcm.tests.util.JsonHelpers.jsonFixture;

public class MessageControllerTest {

  private static final String SINGLE_DEVICE_RECIPIENT = "+14151111111";
  private static final String MULTI_DEVICE_RECIPIENT  = "+14152222222";

  private  final PushSender             pushSender             = mock(PushSender.class            );
  private  final ReceiptSender          receiptSender          = mock(ReceiptSender.class);
  private  final FederatedClientManager federatedClientManager = mock(FederatedClientManager.class);
  private  final AccountsManager        accountsManager        = mock(AccountsManager.class       );
  private  final MessagesManager        messagesManager        = mock(MessagesManager.class);
  private  final RateLimiters           rateLimiters           = mock(RateLimiters.class          );
  private  final RateLimiter            rateLimiter            = mock(RateLimiter.class           );

  private  final ObjectMapper mapper = new ObjectMapper();

  @Rule
  public final ResourceTestRule resources = ResourceTestRule.builder()
                                                            .addProvider(AuthHelper.getAuthenticator())
                                                            .addResource(new MessageController(rateLimiters, pushSender, receiptSender, accountsManager,
                                                                                               messagesManager, federatedClientManager))
                                                            .build();


  @Before
  public void setup() throws Exception {
    Set<Device> singleDeviceList = new HashSet<Device>() {{
      add(new Device(1, "foo", "bar", "baz", "isgcm", null, null, false, 111, null, System.currentTimeMillis()));
    }};

    Set<Device> multiDeviceList = new HashSet<Device>() {{
      add(new Device(1, "foo", "bar", "baz", "isgcm", null, null, false, 222, new SignedPreKey(111, "foo", "bar"), System.currentTimeMillis()));
      add(new Device(2, "foo", "bar", "baz", "isgcm", null, null, false, 333, new SignedPreKey(222, "oof", "rab"), System.currentTimeMillis()));
      add(new Device(3, "foo", "bar", "baz", "isgcm", null, null, false, 444, null, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31)));
    }};

    Account singleDeviceAccount = new Account(SINGLE_DEVICE_RECIPIENT, false, singleDeviceList);
    Account multiDeviceAccount  = new Account(MULTI_DEVICE_RECIPIENT, false, multiDeviceList);

    when(accountsManager.get(eq(SINGLE_DEVICE_RECIPIENT))).thenReturn(Optional.of(singleDeviceAccount));
    when(accountsManager.get(eq(MULTI_DEVICE_RECIPIENT))).thenReturn(Optional.of(multiDeviceAccount));

    when(rateLimiters.getMessagesLimiter()).thenReturn(rateLimiter);
  }

  @Test
  public synchronized void testSingleDeviceLegacy() throws Exception {
    ClientResponse response =
        resources.client().resource("/v1/messages/")
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .entity(mapper.readValue(jsonFixture("fixtures/legacy_message_single_device.json"), IncomingMessageList.class))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .post(ClientResponse.class);

    assertThat("Good Response", response.getStatus(), is(equalTo(200)));

    verify(pushSender, times(1)).sendMessage(any(Account.class), any(Device.class), any(MessageProtos.OutgoingMessageSignal.class));
  }

  @Test
  public synchronized void testSingleDeviceCurrent() throws Exception {
    ClientResponse response =
        resources.client().resource(String.format("/v1/messages/%s", SINGLE_DEVICE_RECIPIENT))
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
        .entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"), IncomingMessageList.class))
        .type(MediaType.APPLICATION_JSON_TYPE)
        .put(ClientResponse.class);

    assertThat("Good Response", response.getStatus(), is(equalTo(200)));

    verify(pushSender, times(1)).sendMessage(any(Account.class), any(Device.class), any(MessageProtos.OutgoingMessageSignal.class));
  }

  @Test
  public synchronized void testMultiDeviceMissing() throws Exception {
    ClientResponse response =
        resources.client().resource(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .entity(mapper.readValue(jsonFixture("fixtures/current_message_single_device.json"), IncomingMessageList.class))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .put(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(409)));

    assertThat("Good Response Body",
               asJson(response.getEntity(MismatchedDevices.class)),
               is(equalTo(jsonFixture("fixtures/missing_device_response.json"))));

    verifyNoMoreInteractions(pushSender);
  }

  @Test
  public synchronized void testMultiDeviceExtra() throws Exception {
    ClientResponse response =
        resources.client().resource(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .entity(mapper.readValue(jsonFixture("fixtures/current_message_extra_device.json"), IncomingMessageList.class))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .put(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(409)));

    assertThat("Good Response Body",
               asJson(response.getEntity(MismatchedDevices.class)),
               is(equalTo(jsonFixture("fixtures/missing_device_response2.json"))));

    verifyNoMoreInteractions(pushSender);
  }

  @Test
  public synchronized void testMultiDevice() throws Exception {
    ClientResponse response =
        resources.client().resource(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .entity(mapper.readValue(jsonFixture("fixtures/current_message_multi_device.json"), IncomingMessageList.class))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .put(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(200)));

    verify(pushSender, times(2)).sendMessage(any(Account.class), any(Device.class), any(MessageProtos.OutgoingMessageSignal.class));
  }

  @Test
  public synchronized void testRegistrationIdMismatch() throws Exception {
    ClientResponse response =
        resources.client().resource(String.format("/v1/messages/%s", MULTI_DEVICE_RECIPIENT))
            .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
            .entity(mapper.readValue(jsonFixture("fixtures/current_message_registration_id.json"), IncomingMessageList.class))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .put(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(410)));

    assertThat("Good Response Body",
               asJson(response.getEntity(StaleDevices.class)),
               is(equalTo(jsonFixture("fixtures/mismatched_registration_id.json"))));

    verifyNoMoreInteractions(pushSender);

  }

  @Test
  public synchronized void testGetMessages() throws Exception {

    final long timestampOne = 313377;
    final long timestampTwo = 313388;

    List<OutgoingMessageEntity> messages = new LinkedList<OutgoingMessageEntity>() {{
      add(new OutgoingMessageEntity(1L, MessageProtos.OutgoingMessageSignal.Type.CIPHERTEXT_VALUE, null, timestampOne, "+14152222222", 2, "hi there".getBytes()));
      add(new OutgoingMessageEntity(2L, MessageProtos.OutgoingMessageSignal.Type.RECEIPT_VALUE, null, timestampTwo, "+14152222222", 2, null));
    }};

    when(messagesManager.getMessagesForDevice(eq(AuthHelper.VALID_NUMBER), eq(1L))).thenReturn(messages);

    OutgoingMessageEntityList response =
        resources.client().resource("/v1/messages/")
                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                 .accept(MediaType.APPLICATION_JSON_TYPE)
                 .get(OutgoingMessageEntityList.class);


    assertEquals(response.getMessages().size(), 2);

    assertEquals(response.getMessages().get(0).getId(), 0);
    assertEquals(response.getMessages().get(1).getId(), 0);

    assertEquals(response.getMessages().get(0).getTimestamp(), timestampOne);
    assertEquals(response.getMessages().get(1).getTimestamp(), timestampTwo);
  }

  @Test
  public synchronized void testDeleteMessages() throws Exception {
    long timestamp = System.currentTimeMillis();
    when(messagesManager.delete(AuthHelper.VALID_NUMBER, "+14152222222", 31337))
        .thenReturn(Optional.of(new OutgoingMessageEntity(31337L,
                                                          MessageProtos.OutgoingMessageSignal.Type.CIPHERTEXT_VALUE,
                                                          null, timestamp,
                                                          "+14152222222", 1, "hi".getBytes())));

    when(messagesManager.delete(AuthHelper.VALID_NUMBER, "+14152222222", 31338))
        .thenReturn(Optional.of(new OutgoingMessageEntity(31337L,
                                                          MessageProtos.OutgoingMessageSignal.Type.RECEIPT_VALUE,
                                                          null, System.currentTimeMillis(),
                                                          "+14152222222", 1, null)));


    when(messagesManager.delete(AuthHelper.VALID_NUMBER, "+14152222222", 31339))
        .thenReturn(Optional.<OutgoingMessageEntity>absent());

    ClientResponse response = resources.client().resource(String.format("/v1/messages/%s/%d", "+14152222222", 31337))
                                       .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                                       .delete(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verify(receiptSender).sendReceipt(any(Account.class), eq("+14152222222"), eq(timestamp), eq(Optional.<String>absent()));

    response = resources.client().resource(String.format("/v1/messages/%s/%d", "+14152222222", 31338))
                        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                        .delete(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verifyNoMoreInteractions(receiptSender);

    response = resources.client().resource(String.format("/v1/messages/%s/%d", "+14152222222", 31339))
                        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                        .delete(ClientResponse.class);

    assertThat("Good Response Code", response.getStatus(), is(equalTo(204)));
    verifyNoMoreInteractions(receiptSender);

  }

}
