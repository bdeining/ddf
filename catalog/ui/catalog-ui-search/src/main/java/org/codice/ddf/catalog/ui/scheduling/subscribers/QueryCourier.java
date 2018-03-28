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

import ddf.catalog.operation.QueryResponse;
import java.util.Map;
import java.util.function.Consumer;

/**
 * This interface represents a service designed to deliver query results to a destination outside of
 * DDF, e.g., an email address.
 *
 * <p><b> This code is experimental. While this interface is functional and tested, it may change or
 * be removed in a future version of the library. </b>
 *
 * @author connor
 */
public interface QueryCourier {
  /**
   * The key used to identify the type of delivery in a JSON object describing a delivery method.
   * This can be used to identify a query delivery service to be used for a delivery method by
   * matching the value of this key to the value returned from {@link
   * QueryCourier#getDeliveryType()}.
   */
  String DELIVERY_TYPE_KEY = "deliveryType";

  /**
   * The key used to identify the display name of a {@link QueryCourier} in the information given to
   * the frontend describing a {@link QueryCourier}.
   */
  String DISPLAY_NAME_KEY = "displayName";

  /**
   * @see QueryCourier#deliver(Map, QueryResponse, String, String, Map, Consumer, Runnable,
   *     Consumer)
   */
  default void deliver(
      Map<String, Object> queryMetacardData,
      QueryResponse queryResults,
      String userID,
      String deliveryID,
      Map<String, Object> parameters) {
    deliver(
        queryMetacardData,
        queryResults,
        userID,
        deliveryID,
        parameters,
        error -> {},
        () -> {},
        warning -> {});
  }

  /**
   * @see QueryCourier#deliver(Map, QueryResponse, String, String, Map, Consumer, Runnable,
   *     Consumer)
   */
  default void deliver(
      Map<String, Object> queryMetacardData,
      QueryResponse queryResults,
      String userID,
      String deliveryID,
      Map<String, Object> parameters,
      Consumer<String> err) {
    deliver(
        queryMetacardData,
        queryResults,
        userID,
        deliveryID,
        parameters,
        err,
        () -> {},
        warning -> {});
  }

  /**
   * @see QueryCourier#deliver(Map, QueryResponse, String, String, Map, Consumer, Runnable,
   *     Consumer)
   */
  default void deliver(
      Map<String, Object> queryMetacardData,
      QueryResponse queryResults,
      String userID,
      String deliveryID,
      Map<String, Object> parameters,
      Consumer<String> err,
      Runnable done) {
    deliver(
        queryMetacardData, queryResults, userID, deliveryID, parameters, err, done, warning -> {});
  }

  /**
   * Deliver the given query results to a destination described by the given parameters.
   *
   * @param queryMetacardData the attributes and values pulled from the query metacard for which the
   *     results were obtained
   * @param queryResults the query results to be sent to the designated destination
   * @param userID the ID of the user effectively running this query
   * @param deliveryID the ID identifying this delivery method in the given user's preferences
   * @param parameters the parameters specific to the called {@link QueryCourier} instance, e.g., an
   *     email address; the contents of this map are expected to have keys matching the names and
   *     values matching the types given by {@link QueryCourier#getRequiredFields()}
   * @param err a callback to be passed an error message if a fatal error should occur while
   *     attempting to complete delivery
   * @param done a callback to be run if the delivery completes successfully
   * @param warn a callback to be passed a warning message if a minor or potential error should
   *     occur while attempting to complete delivery
   */
  void deliver(
      Map<String, Object> queryMetacardData,
      QueryResponse queryResults,
      String userID,
      String deliveryID,
      Map<String, Object> parameters,
      Consumer<String> err,
      Runnable done,
      Consumer<String> warn);

  /**
   * @return a string describing the type of delivery methods that this {@link QueryCourier}
   *     supports. This string is expected to be unique among all available {@link QueryCourier}s.
   */
  String getDeliveryType();

  /**
   * @return a human-readable string naming this {@link QueryCourier} intended to be used for UI
   *     display purposes. This string is not guaranteed to be unique in any way.
   */
  String getDisplayName();

  /**
   * @return a {@link java.util.Map Map} of <tt>String</tt>s to {@link QueryDeliveryDatumType}s
   *     describing all parameters required by this {@link QueryCourier} to successfully complete
   *     its deliveries. The map's keys indicates keys expected to be present in parameters passed
   *     to {@link QueryCourier#deliver(Map, QueryResponse, String, String, Map, Consumer, Runnable,
   *     Consumer)} QueryCourier.deliver}; a particular key's value describes the type of value
   *     expected to be associated with the related key in the same parameters.
   */
  Map<String, QueryDeliveryDatumType> getRequiredFields();
}
