package ddf.security.sts.claimsHandler;

import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.SingleServerSet;
import javax.net.ssl.SSLContext;

public class ServerSetFactory {

  public static SingleServerSet getServerSet(
      String host, int port, LDAPConnectionOptions ldapConnectionOptions, SSLContext sslContext) {
    return new SingleServerSet(host, port, sslContext.getSocketFactory(), ldapConnectionOptions);
  }
}
