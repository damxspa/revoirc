package io.mrarm.irc.upnp;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

public class UPnPDeviceDescription {

    private String mDeviceType;
    private List<UPnPDeviceDescription> mDeviceList;
    private List<Service> mServiceList;

    public static String getElementName(String localName, String qName) {
        if (localName != null && !localName.isEmpty())
            return localName;
        if (qName != null && qName.contains(":"))
            return qName.substring(qName.indexOf(':') + 1);
        return qName;
    }

    public void loadFromUrl(String url) throws IOException, SAXException {
        URLConnection connection = new URL(url).openConnection();
        XMLReader reader;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", true);
            } catch (Throwable ignored) {
            }
            reader = factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
        reader.setContentHandler(new SAXRootParser(reader, this));
        try (java.io.InputStream stream = connection.getInputStream()) {
            reader.parse(new InputSource(stream));
        }
    }

    public String getDeviceType() {
        return mDeviceType;
    }

    public List<UPnPDeviceDescription> getDeviceList() {
        return mDeviceList;
    }

    public List<Service> getServiceList() {
        return mServiceList;
    }


    public UPnPDeviceDescription findDeviceByType(String type) {
        if (mDeviceType != null && mDeviceType.equals(type))
            return this;
        if (mDeviceList == null)
            return null;
        for (UPnPDeviceDescription dev : mDeviceList) {
            UPnPDeviceDescription ret = dev.findDeviceByType(type);
            if (ret != null)
                return ret;
        }
        return null;
    }

    public Service getServiceByType(String type) {
        if (mServiceList == null)
            return null;
        for (Service service : mServiceList) {
            if (service.getServiceType() != null && service.getServiceType().equals(type))
                return service;
        }
        return null;
    }

    public static class Service {

        private String mServiceType;
        private String mControlURL;
        private String mSCPDURL;

        public String getServiceType() {
            return mServiceType;
        }

        public String getControlURL() {
            return mControlURL;
        }

        public String getSCPDURL() {
            return mSCPDURL;
        }

    }



    public static class SAXRootParser extends DefaultHandler {

        private UPnPDeviceDescription mDevice;
        private XMLReader mReader;

        public SAXRootParser(XMLReader reader, UPnPDeviceDescription device) {
            mReader = reader;
            mDevice = device;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            String name = getElementName(localName, qName);
            if (name.equalsIgnoreCase("device")) {
                mReader.setContentHandler(new SAXDeviceParser(mReader, this, mDevice));
            }
        }

    }

    public static class SAXDeviceParser extends DefaultHandler {

        private UPnPDeviceDescription mDevice;
        private XMLReader mReader;
        private ContentHandler mParent;
        private boolean mParsingDeviceList;
        private boolean mParsingServiceList;
        private StringBuilder mContent = new StringBuilder();

        public SAXDeviceParser(XMLReader reader, ContentHandler parent,
                               UPnPDeviceDescription device) {
            mReader = reader;
            mParent = parent;
            mDevice = device;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            mContent.setLength(0);
            String name = getElementName(localName, qName);
            if (name.equalsIgnoreCase("device")) {
                if (!mParsingDeviceList)
                    throw new SAXException("Invalid tree");
                UPnPDeviceDescription child = new UPnPDeviceDescription();
                mDevice.mDeviceList.add(child);
                mReader.setContentHandler(new SAXDeviceParser(mReader, this, child));
            } else if (name.equalsIgnoreCase("deviceList")) {
                if (mParsingDeviceList)
                    throw new SAXException("Invalid tree");
                mParsingDeviceList = true;
                mDevice.mDeviceList = new ArrayList<>();
            } else if (name.equalsIgnoreCase("service")) {
                if (!mParsingServiceList)
                    throw new SAXException("Invalid tree");
                Service child = new Service();
                mDevice.mServiceList.add(child);
                mReader.setContentHandler(new SAXServiceParser(mReader, this, child));
            } else if (name.equalsIgnoreCase("serviceList")) {
                if (mParsingServiceList)
                    throw new SAXException("Invalid tree");
                mParsingServiceList = true;
                mDevice.mServiceList = new ArrayList<>();
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            mContent.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = getElementName(localName, qName);
            if (name.equalsIgnoreCase("deviceType"))
                mDevice.mDeviceType = mContent.toString();
            else if (name.equalsIgnoreCase("deviceList"))
                mParsingDeviceList = false;
            else if (name.equalsIgnoreCase("serviceList"))
                mParsingServiceList = false;
            else if (name.equalsIgnoreCase("device"))
                mReader.setContentHandler(mParent);
        }

    }

    public static class SAXServiceParser extends DefaultHandler {

        private Service mService;
        private XMLReader mReader;
        private ContentHandler mParent;
        private StringBuilder mContent = new StringBuilder();

        public SAXServiceParser(XMLReader reader, ContentHandler parent, Service service) {
            mReader = reader;
            mParent = parent;
            mService = service;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            mContent.setLength(0);
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            mContent.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = getElementName(localName, qName);
            if (name.equalsIgnoreCase("serviceType"))
                mService.mServiceType = mContent.toString();
            else if (name.equalsIgnoreCase("controlURL"))
                mService.mControlURL = mContent.toString();
            else if (name.equalsIgnoreCase("SCPDURL"))
                mService.mSCPDURL = mContent.toString();
            else if (name.equalsIgnoreCase("service"))
                mReader.setContentHandler(mParent);
        }

    }

}
