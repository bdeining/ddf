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
import static org.apache.commons.lang3.Validate.notNull;

import com.google.common.collect.ImmutableMap;
import ddf.catalog.data.Metacard;
import ddf.catalog.operation.QueryResponse;
import ddf.util.MapUtils;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.apache.commons.lang.text.StrLookup;
import org.apache.commons.lang.text.StrSubstitutor;
import org.codice.ddf.platform.email.SmtpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmailQueryCourier implements QueryCourier {
  private static final Logger LOGGER = LoggerFactory.getLogger(EmailQueryCourier.class);

  public static final String DELIVERY_TYPE = "email";

  public static final String DELIVERY_TYPE_DISPLAY_NAME = "Email";

  public static final String EMAIL_PARAMETER_KEY = "email";

  public static final Pattern EMAIL_ADDRESS_PATTERN =
      Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$", Pattern.CASE_INSENSITIVE);

  public static final ImmutableMap<String, QueryDeliveryDatumType> PROPERTIES =
      ImmutableMap.of(EMAIL_PARAMETER_KEY, QueryDeliveryDatumType.EMAIL);

  private String bodyTemplate;

  private String subjectTemplate;

  private String senderEmail;

  private SmtpClient smtpClient;

  public EmailQueryCourier(
      String bodyTemplate, String subjectTemplate, String senderEmail, SmtpClient smtpClient) {
    this.bodyTemplate = bodyTemplate;
    this.subjectTemplate = subjectTemplate;
    this.senderEmail = senderEmail;
    this.smtpClient = smtpClient;
  }

  private static final class MetacardFormatException extends RuntimeException {
    MetacardFormatException(String message) {
      super(message);
    }
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
  public ImmutableMap<String, QueryDeliveryDatumType> getRequiredFields() {
    return PROPERTIES;
  }

  @Override
  public void deliver(
      final Map<String, Object> queryMetacardData,
      final QueryResponse queryResults,
      String username,
      String deliveryID,
      final Map<String, Object> parameters,
      final Consumer<String> err,
      final Runnable done,
      final Consumer<String> warn) {
    LOGGER.trace("Entering EmailQueryCourier.deliver...");

    final StrSubstitutor queryMetacardFormatter =
        new StrSubstitutor(
            new StrLookup() {
              @Override
              public String lookup(String key) {
                if (key.equals("hitCount")) {
                  return String.valueOf(queryResults.getHits());
                } else if (key.startsWith("attribute=")) {
                  final String attributeKey = key.substring(10 /* length of "attribute=" */);
                  if (queryMetacardData.containsKey(attributeKey)) {
                    return String.valueOf(queryMetacardData.get(attributeKey));
                  } else {
                    throw new MetacardFormatException(
                        String.format(
                            "The query metacard does not contain the key \"%s\"!", attributeKey));
                  }
                } else {
                  throw new MetacardFormatException(
                      String.format("The format parameter name \"%s\" is not recognized!", key));
                }
              }
            },
            "%[",
            "]",
            '$');

    MapUtils.tryGetAndRun(
            parameters,
            EMAIL_PARAMETER_KEY,
            String.class,
            email -> {
              LOGGER.debug("Attempting to send query results over email...");

              if (!EMAIL_ADDRESS_PATTERN.matcher(email).matches()) {
                return error("The email address \"%s\" is not a valid email address!", email);
              }

              final InternetAddress senderAddress;
              try {
                senderAddress = new InternetAddress(senderEmail);
              } catch (AddressException exception) {
                return error(
                    "There was a problem preparing the email sender address to send query results: %s",
                    exception.getMessage());
              }

              final InternetAddress destinationAddress;
              try {
                destinationAddress = new InternetAddress(email);
              } catch (AddressException exception) {
                return error(
                    "There was a problem preparing the email destination address to send query results: %s",
                    exception.getMessage());
              }

              return MapUtils.tryGetAndRun(
                  queryMetacardData,
                  Metacard.TITLE,
                  String.class,
                  queryMetacardTitle -> {
                    LOGGER.debug(
                        String.format(
                            "Constructing email to %s for %d results of query \"%s\"...",
                            email, queryResults.getHits(), queryMetacardTitle));

                    final String emailSubject;
                    try {
                      emailSubject = queryMetacardFormatter.replace(subjectTemplate);
                    } catch (MetacardFormatException exception) {
                      return error(
                          "The configured email subject template contained an unrecognized substitution: %s",
                          exception.getMessage());
                    }

                    final String emailBody;
                    try {
                      emailBody = queryMetacardFormatter.replace(bodyTemplate);
                    } catch (MetacardFormatException exception) {
                      return error(
                          "The configured email body template contained an unrecognized substitution: %s",
                          exception.getMessage());
                    }

                    final Session smtpSession = smtpClient.createSession();
                    final MimeMessage message = new MimeMessage(smtpSession);

                    try {
                      message.setFrom(senderAddress);
                      message.addRecipient(Message.RecipientType.TO, destinationAddress);
                      message.setSubject(emailSubject);
                      message.setText(emailBody);
                    } catch (MessagingException exception) {
                      return error(
                          "There was a problem assembling an email message for scheduled query results: %s",
                          exception.getMessage());
                    }

                    LOGGER.debug("Sending email...");
                    smtpClient.send(message);

                    return success();
                  });
            })
        .elseDo(err);
  }

  @SuppressWarnings("unused")
  public void setSenderEmail(String senderEmail) {
    this.senderEmail = senderEmail;
  }

  @SuppressWarnings("unused")
  public void setBodyTemplate(String bodyTemplate) {
    notNull(bodyTemplate, "bodyTemplate must be non-null");
    LOGGER.debug("Setting bodyTemplate : {}", bodyTemplate);
    this.bodyTemplate = bodyTemplate;
  }

  @SuppressWarnings("unused")
  public void setSubjectTemplate(String subjectTemplate) {
    notNull(subjectTemplate, "subjectTemplate must be non-null");
    LOGGER.debug("Setting subjectTemplate : {}", subjectTemplate);
    this.subjectTemplate = subjectTemplate;
  }
}
