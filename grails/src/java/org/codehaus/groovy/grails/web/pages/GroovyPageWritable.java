/* Copyright 2004-2005 Graeme Rocher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.web.pages;

import grails.util.Environment;
import groovy.lang.Binding;
import groovy.lang.GroovyObject;
import groovy.lang.Writable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.DomainClassArtefactHandler;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.commons.GrailsClass;
import org.codehaus.groovy.grails.plugins.GrailsPluginManager;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.WrappedResponseHolder;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * An instance of groovy.lang.Writable that writes itself to the specified
 * writer, typically the response writer
 *
 * @author Graeme Rocher
 * @since 0.5
 * 
 *        <p/>
 *        Created: Feb 23, 2007
 *        Time: 11:36:44 AM
 */
class GroovyPageWritable implements Writable {
    private static final Log LOG = LogFactory.getLog(GroovyPageWritable.class);

    private HttpServletResponse response;
    private HttpServletRequest request;
    private GroovyPageMetaInfo metaInfo;
    private boolean showSource;
    private GrailsWebRequest webRequest;

    private ServletContext context;
    private Map additionalBinding = new HashMap();
    private static final String GROOVY_SOURCE_CONTENT_TYPE = "text/plain";
    private GrailsPluginManager pluginManager;

    public GroovyPageWritable(GroovyPageMetaInfo metaInfo) {
        this.webRequest = (GrailsWebRequest) RequestContextHolder.currentRequestAttributes();
        final ApplicationContext applicationContext = webRequest.getApplicationContext();
        if(applicationContext!=null && applicationContext.containsBean(GrailsPluginManager.BEAN_NAME))
            this.pluginManager = applicationContext.getBean(GrailsPluginManager.BEAN_NAME, GrailsPluginManager.class);
        this.request = webRequest.getCurrentRequest();
        HttpServletResponse wrapped = WrappedResponseHolder.getWrappedResponse();
        this.response = wrapped != null ? wrapped : webRequest.getCurrentResponse();
        this.context = webRequest.getServletContext();
        this.showSource = shouldShowGroovySource(metaInfo);

        this.metaInfo = metaInfo;
        
        
    }

    private boolean shouldShowGroovySource(GroovyPageMetaInfo metaInfo) {
        return request.getParameter("showSource") != null &&
                           (Environment.getCurrent() == Environment.DEVELOPMENT) &&
                            metaInfo.getGroovySource() != null;
    }

    /**
     * This sets any additional variables that need to be placed in the Binding of the GSP page.
     *
     * @param binding The additional variables
     */
    public void setBinding(Map binding) {
        if(binding != null)
            this.additionalBinding = binding;
    }

    /**
     * Set to true if the generated source should be output instead
     * @param showSource True if source output should be output
     */
    public void setShowSource(boolean showSource) {
        this.showSource = showSource;
    }

    

    
    /**
     * Writes the template to the specified Writer
     *
     * @param out The Writer to write to, normally the HttpServletResponse
     * @return Returns the passed Writer
     * @throws IOException
     */
    public Writer writeTo(Writer out) throws IOException {
        if (showSource) {
            // Set it to TEXT
            response.setContentType(GROOVY_SOURCE_CONTENT_TYPE); // must come before response.getOutputStream()
            writeGroovySourceToResponse(metaInfo, out);
        } else {
            // Set it to HTML by default
            if(metaInfo.getCompilationException()!=null) {
                throw metaInfo.getCompilationException();
            }
            boolean  contentTypeAlreadySet = response.isCommitted() || response.getContentType() != null;
            if(LOG.isDebugEnabled() && !contentTypeAlreadySet) {
                LOG.debug("Writing response to ["+response.getClass()+"] with content type: " + metaInfo.getContentType());
            }
            if(!contentTypeAlreadySet)  {
                response.setContentType(metaInfo.getContentType()); // must come before response.getWriter()
            }

            // Set up the script context
            GroovyPageBinding binding = (GroovyPageBinding) request.getAttribute(GrailsApplicationAttributes.PAGE_SCOPE);
            GroovyPageBinding oldBinding = null;

            if(binding == null) {
                binding = createBinding(metaInfo.getPageClass(), pluginManager);
                formulateBinding(request, response, binding, out);
            }
            else {
                // if the Binding already exists then we're a template being included/rendered as part of a larger template
                // in this case we need our own Binding and the old Binding needs to be restored after rendering
                oldBinding = binding;
                binding = createBinding(metaInfo.getPageClass(), pluginManager);
                binding.setPluginContextPath(oldBinding.getPluginContextPath());
                formulateBinding(request, response, binding, out);
            }

            if(metaInfo.getCodecClass() != null) {
            	request.setAttribute("org.codehaus.groovy.grails.GSP_CODEC", metaInfo.getCodecName());
                binding.setVariable("Codec", metaInfo.getCodecClass());
            } else {
            	binding.setVariable("Codec", gspNoneCodeInstance);
            }
            
            GroovyPage page = (GroovyPage) InvokerHelper.createScript(metaInfo.getPageClass(), binding);
            page.setJspTags(metaInfo.getJspTags());
            page.setJspTagLibraryResolver(metaInfo.getJspTagLibraryResolver());
            page.setGspTagLibraryLookup(metaInfo.getTagLibraryLookup());
            page.setHtmlParts(metaInfo.getHtmlParts());
            page.initRun(out, webRequest);
            try {
            	page.run();
            } finally {
            	page.cleanup();
            }
            request.setAttribute(GrailsApplicationAttributes.PAGE_SCOPE, oldBinding);        
        }
        return out;
    }
    
    private static final GspNoneCodec gspNoneCodeInstance = new GspNoneCodec();
    
    private static final class GspNoneCodec {
    	public final Object encode(Object object) {
    		return object;
    	}
    }

    protected void copyBinding(Binding binding, Binding oldBinding, Writer out) throws IOException {
    	formulateBindingFromWebRequest(binding, request, response, out, (GroovyObject) request.getAttribute(GrailsApplicationAttributes.CONTROLLER));
        binding.getVariables().putAll(oldBinding.getVariables());
        for (Object o : additionalBinding.keySet()) {
            String key = (String) o;
            if (!GroovyPage.isReservedName(key)) {
                binding.setVariable(key, additionalBinding.get(key));
            }
            else {
                LOG.debug("Variable [" + key + "] cannot be placed within the GSP model, the name used is a reserved name.");
            }
        }
	}

    private GroovyPageBinding createBinding(Class pageClass, GrailsPluginManager pluginManager) {
        GroovyPageBinding binding = pluginManager != null ? new GroovyPageBinding(pluginManager.getPluginPathForClass(pageClass)) : new GroovyPageBinding(); 
        request.setAttribute(GrailsApplicationAttributes.PAGE_SCOPE, binding);
        binding.setVariable(GroovyPage.PAGE_SCOPE, binding);
        return binding;
    }
    
    /**
     * Copy all of input to output.
     * @param in The input stream to writeInputStreamToResponse from
     * @param out The output to write to
     * @throws IOException When an error occurs writing to the response Writer
     */
    protected void writeInputStreamToResponse(InputStream in, Writer out) throws IOException {
        try {
            in.reset();
            Reader reader = new InputStreamReader(in, "UTF-8");
            char[] buf = new char[8192];

            for (;;) {
                int read = reader.read(buf);
                if (read <= 0) break;
                out.write(buf, 0, read);
            }
        } finally {
            out.close();
            in.close();
        }
    }

    /**
     * Writes the Groovy source code attached to the given info object
     * to the response, prefixing each line with its line number. The
     * line numbers make it easier to match line numbers in exceptions
     * to the generated source.
     * @param info The meta info for the GSP page that we want to write
     * the generated source for.
     * @param out The writer to send the source to.
     * @throws IOException If there is either a problem with the input
     * stream for the Groovy source, or the writer.
     */
    protected void writeGroovySourceToResponse(GroovyPageMetaInfo info, Writer out) throws IOException {
        InputStream in = info.getGroovySource();
        if(in==null) return;
        try {
            try {
                in.reset();
            }
            catch (IOException e) {
                // ignore
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            int lineNum = 1;
            int maxPaddingSize = 3;

            // Prepare the buffer containing the whitespace padding.
            // The padding is used to right-align the line numbers.
            StringBuffer paddingBuffer = new StringBuffer(maxPaddingSize);
            for (int i = 0; i < maxPaddingSize; i++) {
                paddingBuffer.append(' ');
            }

            // Set the initial padding.
            String padding = paddingBuffer.toString();

            // Read the Groovy source line by line and write it to the
            // response, prefixing each line with the line number.
            for (String line = reader.readLine(); line != null; line = reader.readLine(), lineNum++) {
                // Get the current line number as a string.
                String numberText = String.valueOf(lineNum);

                // Decrease the padding if the current line number has
                // more digits than the previous one.
                if (padding.length() + numberText.length() > 4) {
                    paddingBuffer.deleteCharAt(padding.length() - 1);
                    padding = paddingBuffer.toString();
                }

                // Write out this line.
                out.write(padding);
                out.write(numberText);
                out.write(": ");
                out.write(line);
                out.write('\n');
            }
        }
        finally {
            out.close();
            in.close();
        }
    }

    /**
     * Prepare Bindings before instantiating page.
     * @param request The HttpServletRequest instance
     * @param response The HttpServletResponse instance
     * @param out The response out
     * @throws java.io.IOException Thrown when an IO error occurs creating the binding
     */
    protected void formulateBinding(HttpServletRequest request, HttpServletResponse response, Binding binding, Writer out)
            throws IOException {
        formulateBindingFromWebRequest(binding, request, response, out, (GroovyObject) request.getAttribute(GrailsApplicationAttributes.CONTROLLER));
        populateViewModel(request, binding);

    }

    protected void populateViewModel(HttpServletRequest request, Binding binding) {
        // Go through request attributes and add them to the binding as the model
        final Map variables = binding.getVariables();
        for (Enumeration attributeEnum =  request.getAttributeNames(); attributeEnum.hasMoreElements();) {
            String key = (String) attributeEnum.nextElement();
            if(!GroovyPage.isReservedName(key)) {

                if(!variables.containsKey(key)) {
                    binding.setVariable( key, request.getAttribute(key) );
                }
            }
            else {
                LOG.debug("Variable [" + key + "] cannot be placed within the GSP model, the name used is a reserved name.");
            }
        }
        for (Object o : additionalBinding.keySet()) {
            String key = (String) o;
            if (!GroovyPage.isReservedName(key)) {
                binding.setVariable(key, additionalBinding.get(key));
            }
            else {
                LOG.debug("Variable [" + key + "] cannot be placed within the GSP model, the name used is a reserved name.");
            }
        }
    }


    private void formulateBindingFromWebRequest(Binding binding, HttpServletRequest request, HttpServletResponse response, Writer out, GroovyObject controller) {
        // if there is no controller in the request configure using existing attributes, creating objects where necessary
        GrailsWebRequest webRequest = GrailsWebRequest.lookup(request);
        binding.setVariable(GroovyPage.WEB_REQUEST, webRequest);
        binding.setVariable(GroovyPage.REQUEST, request);
        binding.setVariable(GroovyPage.RESPONSE, response);
        binding.setVariable(GroovyPage.FLASH, webRequest.getFlashScope());
        binding.setVariable(GroovyPage.SERVLET_CONTEXT, context);

        ApplicationContext appCtx = webRequest.getAttributes().getApplicationContext();
        binding.setVariable(GroovyPage.APPLICATION_CONTEXT, appCtx);
        if(appCtx!=null) {
            GrailsApplication app = appCtx.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class);
            binding.setVariable(GrailsApplication.APPLICATION_ID, app);
            Map<String,Class> domainClassesWithoutPackage = getDomainClassMap(app);            
            final Map variables = binding.getVariables();
            variables.putAll(domainClassesWithoutPackage);
        }
        binding.setVariable(GroovyPage.SESSION, webRequest.getSession());
        binding.setVariable(GroovyPage.PARAMS, webRequest.getParams());
        binding.setVariable(GroovyPage.ACTION_NAME, webRequest.getActionName());
        binding.setVariable(GroovyPage.CONTROLLER_NAME, webRequest.getControllerName());
        if(controller!= null) {
            binding.setVariable(GrailsApplicationAttributes.CONTROLLER, controller);
        }
        
        binding.setVariable(GroovyPage.OUT, out);
    }

    private static Map<String,Class> cachedDomainsWithoutPackage = null;
    
    private static synchronized Map<String,Class> getDomainClassMap(GrailsApplication application) {
    	Map<String,Class> domainsWithoutPackage = (cachedDomainsWithoutPackage != null) ? cachedDomainsWithoutPackage : new HashMap<String,Class>();
        GrailsClass[] domainClasses = application.getArtefacts(DomainClassArtefactHandler.TYPE);
        if(domainClasses.length!=domainsWithoutPackage.size()) {
            domainsWithoutPackage.clear();
            for (GrailsClass domainClass : domainClasses) {
                final Class theClass = domainClass.getClazz();
                domainsWithoutPackage.put(theClass.getName(), theClass);
            }
	        if(!Environment.isDevelopmentMode()) {
	        	// don't cache in development mode
	        	cachedDomainsWithoutPackage = domainsWithoutPackage;
	        }
        }
        return domainsWithoutPackage;
    }
}
