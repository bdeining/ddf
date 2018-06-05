/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.ui.scheduling.subscribers;

import static ddf.util.Fallible.error;
import static ddf.util.Fallible.success;

import com.google.common.collect.ImmutableMap;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.Result;
import ddf.util.MapUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.codice.ddf.catalog.ui.util.PersistentStoreUtil;
import org.codice.ddf.configuration.SystemBaseUrl;
import org.codice.ddf.persistence.PersistentStore;
import org.codice.ddf.platform.email.SmtpClient;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

public class EmailQueryCourier implements QueryCourier {
  public static final String DELIVERY_TYPE = "email";

  public static final String DELIVERY_TYPE_DISPLAY_NAME = "Email";

  public static final String EMAIL_PARAMETER_KEY = "email";

  public static final Pattern EMAIL_ADDRESS_PATTERN =
      Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE);

  public static final DateTimeFormatter EMAIL_DATE_TIME_FORMATTER =
      DateTimeFormat.forPattern("MM/dd/yyyy HH:mm");

  public static final Map<String, QueryDeliveryDatumType> PROPERTIES =
      ImmutableMap.of(EMAIL_PARAMETER_KEY, QueryDeliveryDatumType.EMAIL);

  private String senderEmail;

  private final PersistentStore persistentStore;

  private final SmtpClient smtpClient;

  public EmailQueryCourier(
      String senderEmail, SmtpClient smtpClient, PersistentStore persistentStore) {
    this.senderEmail = senderEmail;
    this.smtpClient = smtpClient;
    this.persistentStore = persistentStore;
  }

  @Override
  public String getDeliveryType() {
    return DELIVERY_TYPE;
  }

  @Override
  public String getDisplayName() {
    return DELIVERY_TYPE_DISPLAY_NAME;
  }

  @Override
  public Map<String, QueryDeliveryDatumType> getRequiredFields() {
    return PROPERTIES;
  }

  @Override
  public void deliver(
      final String workspaceMetacardId,
      final String queryMetacardId,
      final String queryMetacardTitle,
      final List<Result> queryResults,
      String userID,
      String deliveryID,
      final Map<String, Object> parameters,
      final Consumer<String> err,
      final Runnable done,
      final Consumer<String> warn) {
    MapUtils.tryGetAndRun(
            parameters,
            EMAIL_PARAMETER_KEY,
            String.class,
            email -> {
              if (!EMAIL_ADDRESS_PATTERN.matcher(email).matches()) {
                return error("The email address \"%s\" is not a valid email address!", email);
              }

              final InternetAddress senderAddress;
              try {
                senderAddress = new InternetAddress(senderEmail);
              } catch (AddressException exception) {
                return error(
                    "There was a problem preparing the email sender address to send query results : %s",
                    exception.getMessage());
              }

              final InternetAddress destinationAddress;
              try {
                destinationAddress = new InternetAddress(email);
              } catch (AddressException exception) {
                return error(
                    "There was a problem preparing the email destination address to send query results : %s",
                    exception.getMessage());
              }

              String alertId = UUID.randomUUID().toString();
              String alertUrl =
                  SystemBaseUrl.constructUrl(
                      String.format("/search/catalog/#alerts/%s", alertId).toString());

              String emailBody =
                  String.format(
                      "Your results are ready for query %s. <br><a href='%s'>Link to results</a>",
                      queryMetacardTitle, alertUrl);

              final Session smtpSession = smtpClient.createSession();
              final MimeMessage message = new MimeMessage(smtpSession);

              try {
                message.setFrom(senderAddress);
                message.addRecipient(Message.RecipientType.TO, destinationAddress);
                message.setSubject(
                    String.format("Scheduled query results for \"%s\"", queryMetacardTitle));
                message.setContent(emailBody, "text/html; charset=utf-8");
              } catch (MessagingException exception) {
                return error(
                    "There was a problem assembling an email message for scheduled query results: %s",
                    exception.getMessage());
              }

              smtpClient.send(message);
              addToUserAlerts(workspaceMetacardId, queryMetacardId, userID, alertId, queryResults);

              return success();
            })
        .ifValue(value -> done.run())
        .elseDo(err);
  }

  @SuppressWarnings("unused")
  public void setSenderEmail(String senderEmail) {
    this.senderEmail = senderEmail;
  }

  private void addToUserAlerts(
      final String workspaceMetacardId,
      final String queryMetacardId,
      final String userId,
      final String alertId,
      final List<Result> queryResults) {
    Map<String, Object> preferences =
        PersistentStoreUtil.getUserPreferences(persistentStore, userId);
    List<Map<String, Object>> alerts = (List<Map<String, Object>>) preferences.get("alerts");
    if (alerts.isEmpty()) {
      alerts = new ArrayList<>();
    }
    Map<String, Object> generatedAlert =
        ImmutableMap.<String, Object>builder()
            .put("queryId", queryMetacardId)
            .put("workspaceId", workspaceMetacardId)
            .put("when", DateTime.now().toInstant().getMillis())
            .put(
                "metacardIds",
                queryResults
                    .stream()
                    .map(Result::getMetacard)
                    .map(Metacard::getId)
                    .collect(Collectors.toList()))
            .put("id", alertId)
            .put("serverGenerated", true)
            .build();
    alerts.add(generatedAlert);
    preferences.put("alerts", alerts);
    PersistentStoreUtil.setUserPreferences(persistentStore, userId, preferences);
  }
}
