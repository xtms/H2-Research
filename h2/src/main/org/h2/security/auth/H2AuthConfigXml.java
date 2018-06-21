/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: Alessandro Ventura
 */
package org.h2.security.auth;

import java.io.InputStream;
import java.net.URL;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parser of external authentication XML configuration file
 */
public class H2AuthConfigXml extends DefaultHandler{

    H2AuthConfig result;

    HasConfigProperties lastConfigProperties;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
        switch (qName) {
        case "h2Auth":
            result = new H2AuthConfig();
            result.setAllowUserRegistration("true".equals(
                    getAttributeValueOr("allowUserRegistration",attributes,"false")));
            result.setCreateMissingRoles("true".equals(
                    getAttributeValueOr("createMissingRoles",attributes, "true")));
            break;
        case "realm":
            RealmConfig realmConfig = new RealmConfig();
            realmConfig.setName(getMandatoryAttributeValue("name", attributes));
            realmConfig.setValidatorClass(getMandatoryAttributeValue("validatorClass", attributes));
            result.getRealms().add(realmConfig);
            lastConfigProperties=realmConfig;
            break;
        case "userToRolesMapper":
            UserToRolesMapperConfig userToRolesMapperConfig = new UserToRolesMapperConfig();
            userToRolesMapperConfig.setClassName(getMandatoryAttributeValue("className", attributes));
            result.getUserToRolesMappers().add(userToRolesMapperConfig);
            lastConfigProperties=userToRolesMapperConfig;
            break;
        case "property":
            if (lastConfigProperties==null) {
                throw new SAXException("property element in the wrong place");
            }
            lastConfigProperties.getProperties().add(new PropertyConfig(
                    getMandatoryAttributeValue("name", attributes),
                    getMandatoryAttributeValue("value", attributes)));
            break;
        default:
            throw new SAXException("unexpected element "+qName);
        }
        
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (lastConfigProperties!=null && qName.equals("property")==false) {
            lastConfigProperties=null;
        }
    }

    static String getMandatoryAttributeValue(String attributeName, Attributes attributes) throws SAXException {
        String attributeValue=attributes.getValue(attributeName);
        if (attributeValue==null || attributeValue.trim().equals("")) {
            throw new SAXException("missing attribute "+attributeName);
        }
        return attributeValue;
        
    }

    static String getAttributeValueOr(String attributeName, Attributes attributes, String defaultValue) {
        String attributeValue=attributes.getValue(attributeName);
        if (attributeValue==null || attributeValue.trim().equals("")) {
            return defaultValue;
        }
        return attributeValue;
    }

    public H2AuthConfig getResult() {
        return result;
    }

    /**
     * Parse the xml 
     * @param url
     * @return
     * @throws Exception
     */
    public static H2AuthConfig parseFrom(URL url) throws Exception{
        try (InputStream inputStream= url.openStream()) {
            return parseFrom(inputStream);
        }
    }

    public static H2AuthConfig parseFrom(InputStream inputStream) throws Exception{
        SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();        
        H2AuthConfigXml xmlHandler = new H2AuthConfigXml();
        saxParser.parse(inputStream,xmlHandler);
        return xmlHandler.getResult();
    }
}
