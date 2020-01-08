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

import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.DIGESTMD5BindRequest;
import com.unboundid.ldap.sdk.DIGESTMD5BindRequestProperties;
import com.unboundid.ldap.sdk.GSSAPIBindRequest;
import com.unboundid.ldap.sdk.GSSAPIBindRequestProperties;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.PLAINBindRequest;
import com.unboundid.ldap.sdk.SASLQualityOfProtection;
import com.unboundid.ldap.sdk.SimpleBindRequest;

public class BindMethodChooser {

  public static BindRequest selectBindMethod(
      String bindMethod,
      String bindUserDN,
      String bindUserCredentials,
      String realm,
      String kdcAddress)
      throws LDAPException {
    BindRequest request;
    switch (bindMethod) {
      case "Simple":
        request = new SimpleBindRequest(bindUserDN, bindUserCredentials);
        break;
      case "SASL":
        request = new PLAINBindRequest(bindUserDN, bindUserCredentials);
        break;
      case "GSSAPI SASL":
        GSSAPIBindRequestProperties gssapiBindRequestProperties =
            new GSSAPIBindRequestProperties(bindUserDN, bindUserCredentials);
        gssapiBindRequestProperties.setKDCAddress(kdcAddress);
        gssapiBindRequestProperties.setRealm(realm);
        request = new GSSAPIBindRequest(gssapiBindRequestProperties);
        break;
      case "Digest MD5 SASL":
        DIGESTMD5BindRequestProperties digestmd5BindRequestProperties =
            new DIGESTMD5BindRequestProperties(bindUserDN, bindUserCredentials);
        digestmd5BindRequestProperties.setAllowedQoP(
            SASLQualityOfProtection.AUTH,
            SASLQualityOfProtection.AUTH_CONF,
            SASLQualityOfProtection.AUTH_INT);
        digestmd5BindRequestProperties.setRealm(realm);
        // setCipher(DigestMD5SASLBindRequest.CIPHER_HIGH);
        request = new DIGESTMD5BindRequest(digestmd5BindRequestProperties);
        break;
      default:
        request = new SimpleBindRequest(bindUserDN, bindUserCredentials);
        break;
    }

    return request;
  }
}
