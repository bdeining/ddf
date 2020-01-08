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
package ddf.security.sts.claimsHandler;

import com.google.common.annotations.VisibleForTesting;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.ServerSet;
import ddf.security.claims.Claim;
import ddf.security.claims.ClaimsCollection;
import ddf.security.claims.ClaimsHandler;
import ddf.security.claims.ClaimsParameters;
import ddf.security.claims.impl.ClaimImpl;
import ddf.security.claims.impl.ClaimsCollectionImpl;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;

public class LdapClaimsHandler implements ClaimsHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(LdapClaimsHandler.class);

  private final AttributeMapLoader attributeMapLoader;

  private String propertyFileLocation;

  private ServerSet connectionFactory;

  private String bindUserCredentials;

  private String bindUserDN;

  private String bindMethod;

  private String kerberosRealm;

  private String kdcAddress;

  private Map<String, String> claimMapping;

  private boolean overrideCertDn = false;

  private String objectClass;

  private String userNameAttribute;

  private String userBaseDn;

  private boolean x500FilterEnabled = true;

  public LdapClaimsHandler(AttributeMapLoader attributeMapLoader) {
    super();
    this.attributeMapLoader = attributeMapLoader;
  }

  public ServerSet getLdapConnectionFactory() {
    return connectionFactory;
  }

  public void setLdapConnectionFactory(ServerSet connection) {
    this.connectionFactory = connection;
  }

  public String getPropertyFileLocation() {
    return propertyFileLocation;
  }

  public void setPropertyFileLocation(String propertyFileLocation) {
    if (propertyFileLocation != null
        && !propertyFileLocation.isEmpty()
        && !propertyFileLocation.equals(this.propertyFileLocation)) {
      setClaimsLdapAttributeMapping(attributeMapLoader.buildClaimsMapFile(propertyFileLocation));
    }
    this.propertyFileLocation = propertyFileLocation;
  }

  public Map<String, String> getClaimsLdapAttributeMapping() {
    return this.claimMapping;
  }

  public void setClaimsLdapAttributeMapping(Map<String, String> claimMapping) {
    this.claimMapping = claimMapping;
  }

  public String getObjectClass() {
    return this.objectClass;
  }

  public void setObjectClass(String objectClass) {
    this.objectClass = objectClass;
  }

  public String getUserNameAttribute() {
    return this.userNameAttribute;
  }

  public void setUserNameAttribute(String userNameAttribute) {
    this.userNameAttribute = userNameAttribute;
  }

  public void setUserBaseDN(String userBaseDN) {
    this.userBaseDn = userBaseDN;
  }

  public String getUserBaseDN() {
    return this.userBaseDn;
  }

  public boolean isX500FilterEnabled() {
    return this.x500FilterEnabled;
  }

  public void setX500FilterEnabled(boolean x500FilterEnabled) {
    this.x500FilterEnabled = x500FilterEnabled;
  }

  @Override
  public ClaimsCollection retrieveClaims(ClaimsParameters parameters) {

    Principal principal = parameters.getPrincipal();

    String user = attributeMapLoader.getUser(principal);
    if (user == null) {
      LOGGER.info(
          "Could not determine user name, possible authentication error. Returning no claims.");
      return new ClaimsCollectionImpl();
    }

    ClaimsCollection claimsColl = new ClaimsCollectionImpl();
    LDAPConnection connection = null;
    try {
      AndFilter filter = new AndFilter();
      filter
          .and(new EqualsFilter("objectclass", this.getObjectClass()))
          .and(new EqualsFilter(this.getUserNameAttribute(), user));

      List<String> searchAttributeList = new ArrayList<>();
      for (Map.Entry<String, String> claimEntry : getClaimsLdapAttributeMapping().entrySet()) {
        searchAttributeList.add(claimEntry.getValue());
      }

      String[] searchAttributes;
      searchAttributes = searchAttributeList.toArray(new String[0]);

      connection = connectionFactory.getConnection();
      if (connection != null) {
        BindRequest request = selectBindMethod();
        BindResult bindResult = connection.bind(request);
        if (bindResult.getResultCode() == ResultCode.SUCCESS) {
          String baseDN = attributeMapLoader.getBaseDN(principal, getUserBaseDN(), overrideCertDn);
          LOGGER.trace("Executing ldap search with base dn of {} and filter of {}", baseDN, filter);

          SearchResult entryReader =
              connection.search(baseDN, SearchScope.SUB, filter.toString(), searchAttributes);

          List<SearchResultEntry> searchResultEntries = entryReader.getSearchEntries();

          for (SearchResultEntry searchResultEntry : searchResultEntries) {
            for (Map.Entry<String, String> claimEntry :
                getClaimsLdapAttributeMapping().entrySet()) {
              String claimType = claimEntry.getKey();
              String ldapAttribute = claimEntry.getValue();
              Attribute attr = searchResultEntry.getAttribute(ldapAttribute);
              if (attr == null) {
                LOGGER.trace("Claim '{}' is null", claimType);
              } else {
                Claim claim = new ClaimImpl(claimType);
                String itemValue = attr.getValue();
                if (this.isX500FilterEnabled()) {
                  try {
                    X500Principal x500p = new X500Principal(itemValue);
                    itemValue = x500p.getName();
                    int index = itemValue.indexOf('=');
                    itemValue = itemValue.substring(index + 1, itemValue.indexOf(',', index));
                  } catch (Exception ex) {
                    // Ignore, not X500 compliant thus use the whole
                    // string as the value
                    LOGGER.debug("Not X500 compliant", ex);
                  }
                  claim.addValue(itemValue);
                }

                claimsColl.add(claim);
              }
            }
          }
        } else {
          LOGGER.info("LDAP Connection failed.");
        }
      }
    } catch (LDAPException e) {
      LOGGER.info(
          "Cannot connect to server, therefore unable to set user attributes. Set log level for \"ddf.security.sts.claimsHandler\" to DEBUG for more information");
      LOGGER.debug("Cannot connect to server, therefore unable to set user attributes.", e);
    } finally {
      if (connection != null) {
        connection.close();
      }
    }
    return claimsColl;
  }

  public void disconnect() {
    // connectionFactory.close();
  }

  public void setBindUserDN(String bindUserDN) {
    this.bindUserDN = bindUserDN;
  }

  public void setBindUserCredentials(String bindUserCredentials) {
    this.bindUserCredentials = bindUserCredentials;
  }

  public void setOverrideCertDn(boolean overrideCertDn) {
    this.overrideCertDn = overrideCertDn;
  }

  public void setBindMethod(String bindMethod) {
    this.bindMethod = bindMethod;
  }

  public void setKerberosRealm(String kerberosRealm) {
    this.kerberosRealm = kerberosRealm;
  }

  public void setKdcAddress(String kdcAddress) {
    this.kdcAddress = kdcAddress;
  }

  @VisibleForTesting
  BindRequest selectBindMethod() throws LDAPException {
    return BindMethodChooser.selectBindMethod(
        bindMethod, bindUserDN, bindUserCredentials, kerberosRealm, kdcAddress);
  }
}
