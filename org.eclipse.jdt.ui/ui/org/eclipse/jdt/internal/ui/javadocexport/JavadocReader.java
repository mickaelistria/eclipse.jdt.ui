/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.javadocexport;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.eclipse.core.internal.runtime.Assert;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.jarpackager.JarPackagerMessages;
import org.eclipse.jdt.internal.ui.javadocexport.JavadocSpecificsWizardPage.FlaggedButton;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;


/**
 * Reads data from an InputStream and returns a JarPackage
 */
public class JavadocReader extends Object {

	protected InputStream fInputStream;
	
	/**
	 * Reads a Javadoc Ant Script from the underlying stream.
	 * It is the client's responsiblity to close the stream.
	 */
	public JavadocReader(InputStream inputStream) {
		Assert.isNotNull(inputStream);
		fInputStream= new BufferedInputStream(inputStream);
	}

	/**
     * Closes this stream.
	 * It is the clients responsiblity to close the stream.
	 * 
	 * @exception IOException
     */
    public void close() throws IOException {
    	if (fInputStream != null)
			fInputStream.close();
	}

	public Element readXML() throws IOException, SAXException {
	
	  	DocumentBuilderFactory factory= DocumentBuilderFactory.newInstance();
    	factory.setValidating(false);
		DocumentBuilder parser= null;
		try {
			parser= factory.newDocumentBuilder();
		} catch (ParserConfigurationException ex) {
			throw new IOException(ex.getMessage());
		} finally {
			// Note: Above code is ok since clients are responsible to close the stream
		}
		Element xmlJavadocDesc= parser.parse(new InputSource(fInputStream)).getDocumentElement();
		

		NodeList targets= xmlJavadocDesc.getChildNodes();
		
		for (int i = 0; i < targets.getLength(); i++) {
			Node target= targets.item(i);
			
			//look through the xml file for the javadoc task
			if(target.getNodeName().equals("target")){
				NodeList children= target.getChildNodes();
				for (int j = 0; j < children.getLength(); j++) {
						Node child= children.item(j);
						if(child.getNodeName().equals("javadoc") && (child.getNodeType() == Node.ELEMENT_NODE)){
							return (Element)child;
						}
				}	
			}
		
		}
		return null;
	}

	
	protected boolean getBooleanAttribute(Element element, String name, boolean defaultValue) throws IOException {
		if (element.hasAttribute(name))
			return getBooleanAttribute(element, name);
		else
			return defaultValue;
	}

	protected boolean getBooleanAttribute(Element element, String name) throws IOException {
		String value= element.getAttribute(name);
		if (value != null && value.equalsIgnoreCase("true")) //$NON-NLS-1$
			return true;
		if (value != null && value.equalsIgnoreCase("false")) //$NON-NLS-1$
			return false;
		throw new IOException("Illegal value for boolean attribute"); //$NON-NLS-1$
	}

}
