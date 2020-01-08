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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.OrFilter;

public class RoleClaimsHandler implements ClaimsHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(RoleClaimsHandler.class);

  private final AttributeMapLoader attributeMapLoader;

  private boolean overrideCertDn = false;

  private Map<String, String> claimsLdapAttributeMapping;

  private ServerSet connectionFactory;

  private String delimiter = ";";

  private String objectClass = "groupOfNames";

  private String memberNameAttribute = "member";

  private String membershipUserAttribute = "uid";

  private String loginUserAttribute = "uid";

  private String groupNameAttribute = "cn";

  private String userBaseDn;

  private String groupBaseDn;

  private String roleClaimType = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/role";

  private String propertyFileLocation;

  private String bindUserCredentials;

  private String bindUserDN;

  private String bindMethod;

  private String kerberosRealm;

  private String kdcAddress;

  public RoleClaimsHandler(AttributeMapLoader attributeMapLoader) {
    this.attributeMapLoader = attributeMapLoader;
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

  public String getRoleClaimType() {
    return roleClaimType;
  }

  public void setRoleClaimType(String roleClaimType) {
    this.roleClaimType = roleClaimType;
  }

  public String getGroupNameAttribute() {
    return groupNameAttribute;
  }

  public void setGroupNameAttribute(String groupNameAttribute) {
    this.groupNameAttribute = groupNameAttribute;
  }

  public String getDelimiter() {
    return delimiter;
  }

  public void setDelimiter(String delimiter) {
    this.delimiter = delimiter;
  }

  public String getGroupBaseDn() {
    return groupBaseDn;
  }

  public void setGroupBaseDn(String groupBaseDn) {
    this.groupBaseDn = groupBaseDn;
  }

  public ServerSet getLdapConnectionFactory() {
    return connectionFactory;
  }

  public void setLdapConnectionFactory(ServerSet connection) {
    this.connectionFactory = connection;
  }

  public String getMembershipUserAttribute() {
    return membershipUserAttribute;
  }

  public void setMembershipUserAttribute(String membershipUserAttribute) {
    this.membershipUserAttribute = membershipUserAttribute;
  }

  public String getLoginUserAttribute() {
    return loginUserAttribute;
  }

  public void setLoginUserAttribute(String loginUserAttribute) {
    this.loginUserAttribute = loginUserAttribute;
  }

  public String getObjectClass() {
    return objectClass;
  }

  public void setObjectClass(String objectClass) {
    this.objectClass = objectClass;
  }

  public String getMemberNameAttribute() {
    return memberNameAttribute;
  }

  public void setMemberNameAttribute(String memberNameAttribute) {
    this.memberNameAttribute = memberNameAttribute;
  }

  public String getUserBaseDn() {
    return userBaseDn;
  }

  public void setUserBaseDn(String userBaseDn) {
    this.userBaseDn = userBaseDn;
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

  public Map<String, String> getClaimsLdapAttributeMapping() {
    return claimsLdapAttributeMapping;
  }

  public void setClaimsLdapAttributeMapping(Map<String, String> ldapClaimMapping) {
    this.claimsLdapAttributeMapping = ldapClaimMapping;
  }

  @Override
  public ClaimsCollection retrieveClaims(ClaimsParameters parameters) {
    String[] attributes = {groupNameAttribute, memberNameAttribute};
    ClaimsCollection claimsColl = new ClaimsCollectionImpl();
    LDAPConnection connection = null;
    try {
      Principal principal = parameters.getPrincipal();

      String user = attributeMapLoader.getUser(principal);
      if (user == null) {
        LOGGER.info(
            "Could not determine user name, possible authentication error. Returning no claims.");
        return new ClaimsCollectionImpl();
      }

      connection = connectionFactory.getConnection();
      if (connection != null) {

        BindRequest request =
            BindMethodChooser.selectBindMethod(
                bindMethod, bindUserDN, bindUserCredentials, kerberosRealm, kdcAddress);

        BindResult bindResult = connection.bind(request);

        String membershipValue = user;

        String baseDN = attributeMapLoader.getBaseDN(principal, userBaseDn, overrideCertDn);
        AndFilter filter = new AndFilter();
        filter.and(new EqualsFilter(this.getLoginUserAttribute(), user));
        SearchResult entryReader =
            connection.search(baseDN, SearchScope.SUB, filter.toString(), membershipUserAttribute);
        String userDN = String.format("%s=%s,%s", loginUserAttribute, user, baseDN);
        String specificUserBaseDN = baseDN;

        List<SearchResultEntry> searchResultEntryList = entryReader.getSearchEntries();
        for (SearchResultEntry searchResultEntry : searchResultEntryList) {
          userDN = searchResultEntry.getDN();
          specificUserBaseDN = userDN.substring(userDN.indexOf(',') + 1);
          if (!membershipUserAttribute.equals(loginUserAttribute)) {
            Attribute attr = searchResultEntry.getAttribute(membershipUserAttribute);
            if (attr != null) {
              membershipValue = attr.getValue();
            }
          }
        }

        filter = new AndFilter();
        filter
            .and(new EqualsFilter("objectClass", getObjectClass()))
            .and(
                new OrFilter()
                    .or(
                        new EqualsFilter(
                            getMemberNameAttribute(),
                            getMembershipUserAttribute()
                                + "="
                                + membershipValue
                                + ","
                                + specificUserBaseDN))
                    .or(new EqualsFilter(getMemberNameAttribute(), userDN)));

        if (bindResult.getResultCode() == ResultCode.SUCCESS) {
          LOGGER.trace(
              "Executing ldap search with base dn of {} and filter of {}", groupBaseDn, filter);

          entryReader =
              connection.search(groupBaseDn, SearchScope.SUB, filter.toString(), attributes);

          for (SearchResultEntry searchResultEntry : entryReader.getSearchEntries()) {
            Attribute attr = searchResultEntry.getAttribute(groupNameAttribute);
            if (attr == null) {
              LOGGER.trace("Claim '{}' is null", roleClaimType);
            } else {
              Claim claim = new ClaimImpl(roleClaimType);

              String itemValue = attr.getValue();
              claim.addValue(itemValue);
              claimsColl.add(claim);
            }
          }
        }
      } else {
        LOGGER.info("LDAP Connection failed.");
      }
    } catch (LDAPException e) {
      LOGGER.info(
          "Cannot connect to server, therefore unable to set role claims. Set log level for \"ddf.security.sts.claimsHandler\" to DEBUG for more information.");
      LOGGER.debug("Cannot connect to server, therefore unable to set role claims.", e);
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
}
