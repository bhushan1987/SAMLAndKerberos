package com.eq.saml.exchange;

import com.sun.org.apache.xerces.internal.parsers.DOMParser;
import org.joda.time.DateTime;
import org.opensaml.DefaultBootstrap;
import org.opensaml.common.SAMLVersion;
import org.opensaml.common.SignableSAMLObject;
import org.opensaml.saml2.core.*;
import org.opensaml.saml2.core.validator.ResponseSchemaValidator;
import org.opensaml.saml2.encryption.Decrypter;
import org.opensaml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml2.metadata.SingleSignOnService;
import org.opensaml.saml2.metadata.provider.DOMMetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.opensaml.xml.Configuration;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.encryption.DecryptionException;
import org.opensaml.xml.encryption.InlineEncryptedKeyResolver;
import org.opensaml.xml.io.Marshaller;
import org.opensaml.xml.io.MarshallingException;
import org.opensaml.xml.io.UnmarshallingException;
import org.opensaml.xml.schema.XSAny;
import org.opensaml.xml.schema.XSString;
import org.opensaml.xml.schema.impl.XSAnyImpl;
import org.opensaml.xml.security.credential.BasicCredential;
import org.opensaml.xml.security.credential.Credential;
import org.opensaml.xml.security.credential.UsageType;
import org.opensaml.xml.security.keyinfo.KeyInfoHelper;
import org.opensaml.xml.security.keyinfo.StaticKeyInfoCredentialResolver;
import org.opensaml.xml.security.x509.BasicX509Credential;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.SignatureValidator;
import org.opensaml.xml.signature.X509Data;
import org.opensaml.xml.util.XMLHelper;
import org.opensaml.xml.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.xml.bind.JAXBIntrospector.getValue;

public class SamlClient {
  private static final Logger logger = LoggerFactory.getLogger(SamlClient.class);
  private static boolean initializedOpenSaml = false;
  private static KeyPair keyPair;

  public enum SamlIdpBinding {
    POST,
    Redirect;
  }

  private String relyingPartyIdentifier;
  private String assertionConsumerServiceUrl;
  private String identityProviderUrl;
  private String responseIssuer;
  private List<Credential> credentials;
  private DateTime now; // used for testing only
  private long notBeforeSkew = 0L;
  private SamlIdpBinding samlBinding;

  /**
   * Returns the url where SAML requests should be posted.
   *
   * @return the url where SAML requests should be posted.
   */
  public String getIdentityProviderUrl() {
    return identityProviderUrl;
  }

  /**
   * Sets the date that will be considered as now. This is only useful for testing.
   *
   * @param now the date to use for now.
   */
  public void setDateTimeNow(DateTime now) {
    this.now = now;
  }

  /**
   * Sets by how much the current time can be before the assertion's notBefore.
   *
   * Used to mitigate clock differences between the identity provider and relying party.
   *
   * @param notBeforeSkew non-negative amount of skew (in milliseconds) to allow between the
   *                      current time and the assertion's notBefore date. Default: 0
   */
  public void setNotBeforeSkew(long notBeforeSkew) {
    if (notBeforeSkew < 0) {
      throw new IllegalArgumentException("Skew must be non-negative");
    }
    this.notBeforeSkew = notBeforeSkew;
  }

  /**
   * Constructs an SAML client using explicit parameters.
   *
   * @param relyingPartyIdentifier      the identifier of the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param identityProviderUrl         the url where the SAML request will be submitted.
   * @param responseIssuer              the expected issuer ID for SAML responses.
   * @param certificates                the list of base-64 encoded certificates to use to validate
   *                                    responses.
   * @param samlBinding                 what type of SAML binding should the client use.
   * @throws SamlException thrown if any error occur while loading the provider information.
   */
  public SamlClient(
          String relyingPartyIdentifier,
          String assertionConsumerServiceUrl,
          String identityProviderUrl,
          String responseIssuer,
          List<X509Certificate> certificates,
          SamlIdpBinding samlBinding)
          throws SamlException {

    ensureOpenSamlIsInitialized();

    if (relyingPartyIdentifier == null) {
      throw new IllegalArgumentException("relyingPartyIdentifier");
    }
    if (identityProviderUrl == null) {
      throw new IllegalArgumentException("identityProviderUrl");
    }
    if (responseIssuer == null) {
      throw new IllegalArgumentException("responseIssuer");
    }
    if (certificates == null || certificates.isEmpty()) {
      throw new IllegalArgumentException("certificates");
    }

    this.relyingPartyIdentifier = relyingPartyIdentifier;
    this.assertionConsumerServiceUrl = assertionConsumerServiceUrl;
    this.identityProviderUrl = identityProviderUrl;
    this.responseIssuer = responseIssuer;
    credentials = certificates.stream().map(SamlClient::getCredential).collect(Collectors.toList());
    this.samlBinding = samlBinding;
  }

  /**
   * Constructs an SAML client using explicit parameters.
   *
   * @param relyingPartyIdentifier      the identifier of the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param identityProviderUrl         the url where the SAML request will be submitted.
   * @param responseIssuer              the expected issuer ID for SAML responses.
   * @param certificates                the list of base-64 encoded certificates to use to validate
   *                                    responses.
   * @throws SamlException thrown if any error occur while loading the provider information.
   */
  public SamlClient(
          String relyingPartyIdentifier,
          String assertionConsumerServiceUrl,
          String identityProviderUrl,
          String responseIssuer,
          List<X509Certificate> certificates)
          throws SamlException {

    this(
            relyingPartyIdentifier,
            assertionConsumerServiceUrl,
            identityProviderUrl,
            responseIssuer,
            certificates,
            SamlIdpBinding.POST);
  }

  /**
   * Constructs an SAML client using explicit parameters.
   *
   * @param relyingPartyIdentifier      the identifier of the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param identityProviderUrl         the url where the SAML request will be submitted.
   * @param responseIssuer              the expected issuer ID for SAML responses.
   * @param certificate                 the base-64 encoded certificate to use to validate
   *                                    responses.
   * @throws SamlException thrown if any error occur while loading the provider information.
   */
  public SamlClient(
          String relyingPartyIdentifier,
          String assertionConsumerServiceUrl,
          String identityProviderUrl,
          String responseIssuer,
          X509Certificate certificate)
          throws SamlException {

    this(
            relyingPartyIdentifier,
            assertionConsumerServiceUrl,
            identityProviderUrl,
            responseIssuer,
            Collections.singletonList(certificate),
            SamlIdpBinding.POST);
  }

  /**
   * Builds an encoded SAML request.
   *
   * @return The base-64 encoded SAML request.
   * @throws SamlException thrown if an unexpected error occurs.
   */
  public String getSamlRequest() throws SamlException {
    AuthnRequest request = (AuthnRequest) buildSamlObject(AuthnRequest.DEFAULT_ELEMENT_NAME);
    request.setID("z" + UUID.randomUUID().toString()); // ADFS needs IDs to start with a letter

    request.setVersion(SAMLVersion.VERSION_20);
    request.setIssueInstant(new DateTime());
    request.setProtocolBinding(
            "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-" + this.samlBinding.toString());
    request.setAssertionConsumerServiceURL(assertionConsumerServiceUrl);

    Issuer issuer = (Issuer) buildSamlObject(Issuer.DEFAULT_ELEMENT_NAME);
    issuer.setValue(relyingPartyIdentifier);
    request.setIssuer(issuer);

    NameIDPolicy nameIDPolicy = (NameIDPolicy) buildSamlObject(NameIDPolicy.DEFAULT_ELEMENT_NAME);
    nameIDPolicy.setFormat("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified");
    request.setNameIDPolicy(nameIDPolicy);

    StringWriter stringWriter = new StringWriter();
    try {
      Marshaller marshaller = Configuration.getMarshallerFactory().getMarshaller(request);
      Element dom = marshaller.marshall(request);
      XMLHelper.writeNode(dom, stringWriter);
    } catch (MarshallingException ex) {
      throw new SamlException("Error while marshalling SAML request to XML", ex);
    }

    logger.trace("Issuing SAML request: " + stringWriter.toString());

    try {
      return Base64.getEncoder().encodeToString(stringWriter.toString().getBytes("UTF-8"));
    } catch (UnsupportedEncodingException ex) {
      throw new SamlException("Error while encoding SAML request", ex);
    }
  }

  /**
   * Decodes and validates an SAML response returned by an identity provider.
   *
   * @param encodedResponse the encoded response returned by the identity provider.
   * @return An {@link SamlResponse} object containing information decoded from the SAML response.
   * @throws SamlException if the signature is invalid, or if any other error occurs.
   */
  public SamlResponse decodeAndValidateSamlResponse(String encodedResponse) throws SamlException, DecryptionException {
    String decodedResponse;
    try {
      decodedResponse = new String(Base64.getDecoder().decode(encodedResponse), "UTF-8");
    } catch (UnsupportedEncodingException ex) {
      throw new SamlException("Cannot decode base64 encoded response", ex);
    }

    logger.trace("Validating SAML response: " + decodedResponse);

    Response response;
    try {
      DOMParser parser = createDOMParser();
      parser.parse(new InputSource(new StringReader(decodedResponse)));
      response =
              (Response)
                      Configuration.getUnmarshallerFactory()
                              .getUnmarshaller(parser.getDocument().getDocumentElement())
                              .unmarshall(parser.getDocument().getDocumentElement());
    } catch (IOException | SAXException | UnmarshallingException ex) {
      throw new SamlException("Cannot decode xml encoded response", ex);
    }

    validateResponse(response);
    validateAssertion(response);
    validateSignature(response);

    EncryptedAssertion encryptedAssertion = response.getEncryptedAssertions().get(0);
    Assertion assertion = decryptAssertion(encryptedAssertion);
    return new SamlResponse(assertion);
  }

  /**
   * Redirects an {@link HttpServletResponse} to the configured identity provider.
   *
   * @param response   The {@link HttpServletResponse}.
   * @param relayState Optional relay state that will be passed along.
   * @throws IOException   thrown if an IO error occurs.
   * @throws SamlException thrown is an unexpected error occurs.
   */
  public void redirectToIdentityProvider(HttpServletResponse response, String relayState)
          throws IOException, SamlException {
    Map<String, String> values = new HashMap<>();
    String samlRequest = getSamlRequest();
    values.put("SAMLRequest", samlRequest);
    if (relayState != null) {
      values.put("RelayState", relayState);
    }

    BrowserUtils.postUsingBrowser(identityProviderUrl, response, values);
  }

  /**
   * Processes a POST containing the SAML response.
   *
   * @param request the {@link HttpServletRequest}.
   * @return An {@link SamlResponse} object containing information decoded from the SAML response.
   * @throws SamlException thrown is an unexpected error occurs.
   */
  public SamlResponse processPostFromIdentityProvider(HttpServletRequest request)
          throws SamlException, DecryptionException {
    String encodedResponse = request.getParameter("SAMLResponse");
    return decodeAndValidateSamlResponse(encodedResponse);
  }

  /**
   * Constructs an SAML client using XML metadata obtained from the identity provider. <p> When
   * using Okta as an identity provider, it is possible to pass null to relyingPartyIdentifier and
   * assertionConsumerServiceUrl; they will be inferred from the metadata provider XML.
   *
   * @param relyingPartyIdentifier      the identifier for the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param metadata                    the XML metadata obtained from the identity provider.
   * @return The created {@link SamlClient}.
   * @throws SamlException thrown if any error occur while loading the metadata information.
   */
  public static SamlClient fromMetadata(
          String relyingPartyIdentifier, String assertionConsumerServiceUrl, Reader metadata)
          throws SamlException {
    return fromMetadata(
            relyingPartyIdentifier, assertionConsumerServiceUrl, metadata, SamlIdpBinding.POST);
  }

  /**
   * Constructs an SAML client using XML metadata obtained from the identity provider. <p> When
   * using Okta as an identity provider, it is possible to pass null to relyingPartyIdentifier and
   * assertionConsumerServiceUrl; they will be inferred from the metadata provider XML.
   *
   * @param relyingPartyIdentifier      the identifier for the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param metadata                    the XML metadata obtained from the identity provider.
   * @param samlBinding                 the HTTP method to use for binding to the IdP.
   * @return The created {@link SamlClient}.
   * @throws SamlException thrown if any error occur while loading the metadata information.
   */
  public static SamlClient fromMetadata(
          String relyingPartyIdentifier,
          String assertionConsumerServiceUrl,
          Reader metadata,
          SamlIdpBinding samlBinding)
          throws SamlException {
    return fromMetadata(
            relyingPartyIdentifier, assertionConsumerServiceUrl, metadata, samlBinding, null);
  }

  /**
   * Constructs an SAML client using XML metadata obtained from the identity provider. <p> When
   * using Okta as an identity provider, it is possible to pass null to relyingPartyIdentifier and
   * assertionConsumerServiceUrl; they will be inferred from the metadata provider XML.
   *
   * @param relyingPartyIdentifier      the identifier for the relying party.
   * @param assertionConsumerServiceUrl the url where the identity provider will post back the
   *                                    SAML response.
   * @param metadata                    the XML metadata obtained from the identity provider.
   * @param samlBinding                 the HTTP method to use for binding to the IdP.
   * @return The created {@link SamlClient}.
   * @throws SamlException thrown if any error occur while loading the metadata information.
   */
  public static SamlClient fromMetadata(
          String relyingPartyIdentifier,
          String assertionConsumerServiceUrl,
          Reader metadata,
          SamlIdpBinding samlBinding,
          List<X509Certificate> certificates)
          throws SamlException {

    try {
      loadKeys();
    } catch (Exception e) {
      e.printStackTrace();
    }
    ensureOpenSamlIsInitialized();

    MetadataProvider metadataProvider = createMetadataProvider(metadata);
    EntityDescriptor entityDescriptor = getEntityDescriptor(metadataProvider);

    IDPSSODescriptor idpSsoDescriptor = getIDPSSODescriptor(entityDescriptor);
    SingleSignOnService idpBinding = getIdpBinding(idpSsoDescriptor, samlBinding);
    List<X509Certificate> x509Certificates = getCertificates(idpSsoDescriptor);
    boolean isOkta = entityDescriptor.getEntityID().contains(".okta.com");

    if (relyingPartyIdentifier == null) {
      // Okta's own toolkit uses the entity ID as a relying party identifier, so if we
      // detect that the IDP is Okta let's tolerate a null value for this parameter.
      if (isOkta) {
        relyingPartyIdentifier = entityDescriptor.getEntityID();
      } else {
        throw new IllegalArgumentException("relyingPartyIdentifier");
      }
    }

    if (assertionConsumerServiceUrl == null && isOkta) {
      // Again, Okta's own toolkit uses this value for the assertion consumer url, which
      // kinda makes no sense since this is supposed to be a url pointing to a server
      // outside Okta, but it probably just straight ignores this and use the one from
      // it's own config anyway.
      assertionConsumerServiceUrl = idpBinding.getLocation();
    }

    if (certificates != null) {
      // Adding certificates given to this method
      // because some idp metadata file does not embedded signing certificate
      x509Certificates.addAll(certificates);
    }

    String identityProviderUrl = idpBinding.getLocation();
    String responseIssuer = entityDescriptor.getEntityID();

    return new SamlClient(
            relyingPartyIdentifier,
            assertionConsumerServiceUrl,
            identityProviderUrl,
            responseIssuer,
            x509Certificates,
            samlBinding);
  }

  private void validateResponse(Response response) throws SamlException {
    try {
      new ResponseSchemaValidator().validate(response);
    } catch (ValidationException ex) {
      throw new SamlException("The response schema validation failed", ex);
    }

    if (!response.getIssuer().getValue().equals(responseIssuer)) {
      throw new SamlException("The response issuer didn't match the expected value");
    }

    String statusCode = response.getStatus().getStatusCode().getValue();

    if (!statusCode.equals("urn:oasis:names:tc:SAML:2.0:status:Success")) {
      throw new SamlException("Invalid status code: " + statusCode);
    }
  }

  private void validateAssertion(Response response) throws SamlException, DecryptionException {
    if (response.getEncryptedAssertions().size() != 1) {
      throw new SamlException("The response doesn't contain exactly 1 assertion");
    }

    EncryptedAssertion encryptedAssertion = response.getEncryptedAssertions().get(0);
    Assertion assertion = decryptAssertion(encryptedAssertion);

    if (!assertion.getIssuer().getValue().equals(responseIssuer)) {
      throw new SamlException("The assertion issuer didn't match the expected value");
    }

    if (assertion.getSubject().getNameID() == null) {
      throw new SamlException(
              "The NameID value is missing from the SAML response; this is likely an IDP configuration issue");
    }

    enforceConditions(assertion.getConditions());
  }

  private void enforceConditions(Conditions conditions) throws SamlException {
    DateTime now = this.now != null ? this.now : new DateTime();

    DateTime notBefore = conditions.getNotBefore();
    DateTime skewedNotBefore = notBefore.minus(notBeforeSkew);
    if (now.isBefore(skewedNotBefore)) {
      throw new SamlException("The assertion cannot be used before " + notBefore.toString());
    }

    DateTime notOnOrAfter = conditions.getNotOnOrAfter();
    if (now.isAfter(notOnOrAfter)) {
      throw new SamlException("The assertion cannot be used after  " + notOnOrAfter.toString());
    }
  }

  private void validateSignature(Response response) throws SamlException, DecryptionException {
    EncryptedAssertion encryptedAssertion = response.getEncryptedAssertions().get(0);
    Assertion assertion = decryptAssertion(encryptedAssertion);

    Signature assertionSignature = assertion.getSignature();

    if (assertionSignature == null && assertionSignature == null) {
      throw new SamlException("No signature is present in either response or assertion");
    }

    if (assertionSignature != null && !validate(assertionSignature)) {
      throw new SamlException("The response signature is invalid");
    }

    if (assertionSignature != null && !validate(assertionSignature)) {
      throw new SamlException("The assertion signature is invalid");
    }
  }

  private boolean validate(Signature signature) {
    if (signature == null) {
      return false;
    }

    // It's fine if any of the credentials match the signature
    return credentials
            .stream()
            .anyMatch(
                    c -> {
                      try {
                        SignatureValidator signatureValidator = new SignatureValidator(c);
                        signatureValidator.validate(signature);
                        return true;
                      } catch (ValidationException ex) {
                        return false;
                      }
                    });
  }

  private synchronized static void ensureOpenSamlIsInitialized() throws SamlException {
    if (!initializedOpenSaml) {
      try {
        DefaultBootstrap.bootstrap();
        initializedOpenSaml = true;
      } catch (Throwable ex) {
        throw new SamlException("Error while initializing the Open SAML library", ex);
      }
    }
  }

  private static DOMParser createDOMParser() throws SamlException {
    DOMParser parser =
            new DOMParser() {
              {
                try {
                  setFeature(INCLUDE_COMMENTS_FEATURE, false);
                } catch (Throwable ex) {
                  throw new SamlException(
                          "Cannot disable comments parsing to mitigate https://www.kb.cert.org/vuls/id/475445",
                          ex);
                }
              }
            };

    return parser;
  }

  private static MetadataProvider createMetadataProvider(Reader metadata) throws SamlException {
    try {
      DOMParser parser = createDOMParser();
      parser.parse(new InputSource(metadata));
      DOMMetadataProvider provider =
              new DOMMetadataProvider(parser.getDocument().getDocumentElement());
      provider.initialize();
      return provider;
    } catch (IOException | SAXException | MetadataProviderException ex) {
      throw new SamlException("Cannot load identity provider metadata", ex);
    }
  }

  private static EntityDescriptor getEntityDescriptor(MetadataProvider metadataProvider)
          throws SamlException {
    EntityDescriptor descriptor;

    try {
      descriptor = (EntityDescriptor) metadataProvider.getMetadata();
    } catch (MetadataProviderException ex) {
      throw new SamlException("Cannot retrieve the entity descriptor", ex);
    }

    if (descriptor == null) {
      throw new SamlException("Cannot retrieve the entity descriptor");
    }

    return descriptor;
  }

  private static IDPSSODescriptor getIDPSSODescriptor(EntityDescriptor entityDescriptor)
          throws SamlException {
    IDPSSODescriptor idpssoDescriptor =
            entityDescriptor.getIDPSSODescriptor("urn:oasis:names:tc:SAML:2.0:protocol");
    if (idpssoDescriptor == null) {
      throw new SamlException("Cannot retrieve IDP SSO descriptor");
    }

    return idpssoDescriptor;
  }

  private static SingleSignOnService getIdpBinding(
          IDPSSODescriptor idpSsoDescriptor, SamlIdpBinding samlBinding) throws SamlException {
    return idpSsoDescriptor
            .getSingleSignOnServices()
            .stream()
            .filter(
                    x
                            -> x.getBinding()
                            .equals("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-" + samlBinding.toString()))
            .findAny()
            .orElseThrow(() -> new SamlException("Cannot find HTTP-POST SSO binding in metadata"));
  }

  private static List<X509Certificate> getCertificates(IDPSSODescriptor idpSsoDescriptor)
          throws SamlException {

    List<X509Certificate> certificates;

    try {
      certificates =
              idpSsoDescriptor
                      .getKeyDescriptors()
                      .stream()
                      .filter(x -> x.getUse() == UsageType.SIGNING)
                      .flatMap(SamlClient::getDatasWithCertificates)
                      .map(SamlClient::getFirstCertificate)
                      .collect(Collectors.toList());

    } catch (Exception e) {
      throw new SamlException("Exception in getCertificates", e);
    }

    return certificates;
  }

  private static Stream<X509Data> getDatasWithCertificates(KeyDescriptor descriptor) {
    return descriptor
            .getKeyInfo()
            .getX509Datas()
            .stream()
            .filter(d -> d.getX509Certificates().size() > 0);
  }

  private static X509Certificate getFirstCertificate(X509Data data) {
    try {
      org.opensaml.xml.signature.X509Certificate cert =
              data.getX509Certificates().stream().findFirst().orElse(null);
      if (cert != null) {
        return KeyInfoHelper.getCertificate(cert);
      }
    } catch (CertificateException e) {
      logger.error("Exception in getFirstCertificate", e);
    }

    return null;
  }

  private static Credential getCredential(X509Certificate certificate) {
    BasicX509Credential credential = new BasicX509Credential();
    credential.setEntityCertificate(certificate);
    credential.setPublicKey(certificate.getPublicKey());
    credential.setCRLs(Collections.emptyList());
    return credential;
  }

  private static XMLObject buildSamlObject(QName qname) {
    return Configuration.getBuilderFactory().getBuilder(qname).buildObject(qname);
  }

  public Assertion decryptAssertion(EncryptedAssertion encryptedAssertion) throws DecryptionException {

    BasicCredential basicCredential = new BasicCredential();
    basicCredential.setPrivateKey(keyPair.getPrivate());

    StaticKeyInfoCredentialResolver keyInfoCredentialResolver = new StaticKeyInfoCredentialResolver(basicCredential);

    Decrypter decrypter = new Decrypter(null, keyInfoCredentialResolver, new InlineEncryptedKeyResolver());
    decrypter.setRootInNewDocument(true);
    return decrypter.decrypt(encryptedAssertion);
  }


  public static void loadKeys() throws Exception {
    FileInputStream is = new FileInputStream("D:\\Security Service Framework\\POC\\SAML_ADFS_TEST\\equbess.p12");

    KeyStore keystore = KeyStore.getInstance("PKCS12");
    keystore.load(is, "dkpune".toCharArray());
    is.close();

    String alias = "eQubeSS";

    Key key = keystore.getKey(alias, "dkpune".toCharArray());
    if (key instanceof PrivateKey) {
      // Get certificate of public key
      Certificate cert = keystore.getCertificate(alias);

      // Get public key
      PublicKey publicKey = cert.getPublicKey();

      // Return a key pair
      keyPair = new KeyPair(publicKey, (PrivateKey) key);
    }
  }

  public String write(Object object) throws Exception {
    String xmlString = null;
    if (object instanceof XMLObject) {
      XMLObject xmlObject = (XMLObject) object;
      Element element = null;

      if (object instanceof SignableSAMLObject && ((SignableSAMLObject) object).isSigned() && xmlObject.getDOM() != null) {
        element = xmlObject.getDOM();
      } else {
        try {
          Marshaller out = Configuration.getMarshallerFactory().getMarshaller(xmlObject);
          out.marshall(xmlObject);
          element = xmlObject.getDOM();

        } catch (Exception e) {
          throw e;
        }

      }
      try {

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StreamResult result = new StreamResult(new StringWriter());
        DOMSource source = new DOMSource(element);
        transformer.transform(source, result);
        xmlString = result.getWriter().toString();
        return xmlString;
      } catch (Exception e) {
        throw e;
      }
    }
    return xmlString;
  }

  public Map<String, Object> getAccordingToValues(Attribute attribute, List<XMLObject> xmlObjects) {
    Map<String, Object> objectMap = new HashMap<>();
    Object obj = null;
    if (xmlObjects != null) {
      if (xmlObjects.isEmpty()) {
        objectMap.put(attribute.getName(), null);
      } else if (xmlObjects.size() == 1) {
        obj = getValue(xmlObjects.get(0));
      }
      objectMap.put(attribute.getName(), obj);
    } else {
      objectMap.put(attribute.getName(), null);
    }
    return objectMap;
  }

  private Object getValue(XMLObject xmlObject) {
    if (xmlObject instanceof XSString) {
      return getStringValue(xmlObject);
    } else if (xmlObject instanceof XSAny) {
      return  ((XSAnyImpl) ((XSAny) xmlObject)).getTextContent();
    }
    return null;
  }

  private Object getStringValue(XMLObject xmlObject) {
    return ((XSString) xmlObject).getValue();
  }
}
