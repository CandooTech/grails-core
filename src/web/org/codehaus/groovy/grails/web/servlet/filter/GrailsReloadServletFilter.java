/*
 * Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.web.servlet.filter;

import groovy.lang.GroovyClassLoader;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.commons.GrailsControllerClass;
import org.codehaus.groovy.grails.commons.GrailsDomainClass;
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty;
import org.codehaus.groovy.grails.commons.GrailsPageFlowClass;
import org.codehaus.groovy.grails.commons.GrailsServiceClass;
import org.codehaus.groovy.grails.commons.GrailsTagLibClass;
import org.codehaus.groovy.grails.commons.spring.GrailsRuntimeConfigurator;
import org.codehaus.groovy.grails.commons.spring.GrailsWebApplicationContext;
import org.codehaus.groovy.grails.plugins.GrailsPluginManager;
import org.codehaus.groovy.grails.plugins.PluginManagerHolder;
import org.codehaus.groovy.grails.scaffolding.GrailsTemplateGenerator;
import org.codehaus.groovy.grails.scaffolding.ScaffoldDomain;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsUrlHandlerMapping;
import org.codehaus.groovy.grails.web.servlet.mvc.SimpleGrailsController;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.WebRequestInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;

/**
 * A servlet filter that copies resources from the source on content change and manages reloading if necessary
 *
 * @author Graeme Rocher
 * @since Jan 10, 2006
 */
public class GrailsReloadServletFilter extends OncePerRequestFilter {

    public static final Log LOG = LogFactory.getLog(GrailsReloadServletFilter.class);

    ResourceCopier copyScript;
    GrailsWebApplicationContext context;
    GrailsApplication application;
    private boolean initialised = false;
    private Map resourceMetas = Collections.synchronizedMap(new HashMap());
    private GrailsTemplateGenerator templateGenerator;

	private GrailsRuntimeConfigurator config;

	private GrailsPluginManager manager;

    class ResourceMeta {
        long lastModified;
        String className;
        Class clazz;
        URL url;
    }

    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {
      context = (GrailsWebApplicationContext)getServletContext().getAttribute(GrailsApplicationAttributes.APPLICATION_CONTEXT);

      if(LOG.isDebugEnabled()) {
	      LOG.debug("Executing Grails reload filter...");
      }
      if(context == null) {
          filterChain.doFilter(httpServletRequest,httpServletResponse);
          return;
      }
      application = (GrailsApplication)context.getBean(GrailsApplication.APPLICATION_ID);
      if(application == null) {
          filterChain.doFilter(httpServletRequest,httpServletResponse);
          return;
      }      
      if(config == null) {
    	  WebApplicationContext parent = (WebApplicationContext)getServletContext().getAttribute(GrailsApplicationAttributes.PARENT_APPLICATION_CONTEXT);
    	  config = new GrailsRuntimeConfigurator(application,parent);  
      }
      
      

      if(copyScript == null) {
          GroovyClassLoader gcl = new GroovyClassLoader(getClass().getClassLoader());

          Class groovyClass;
          try {
              groovyClass = gcl.parseClass(gcl.getResource("org/codehaus/groovy/grails/web/servlet/filter/GrailsResourceCopier.groovy").openStream());
              copyScript = (ResourceCopier)groovyClass.newInstance();
              groovyClass = gcl.loadClass("org.codehaus.groovy.grails.scaffolding.DefaultGrailsTemplateGenerator");
              templateGenerator = (GrailsTemplateGenerator)groovyClass.newInstance();
              templateGenerator.setOverwrite(true);
              // perform initial generation of views
              GrailsControllerClass[] controllers = application.getControllers();
              for (int i = 0; i < controllers.length; i++) {
                  GrailsControllerClass controller = controllers[i];
                  if(controller.isScaffolding()) {
                    Class clazz = controller.getScaffoldedClass();
                    GrailsDomainClass domainClass;
                    if(clazz != null) {
                       domainClass = application.getGrailsDomainClass(clazz.getName());
                    }
                    else {
                       domainClass = application.getGrailsDomainClass(controller.getName());
                    }
                    if(domainClass != null) {
                        // generate new views
                        templateGenerator.generateViews(domainClass,getServletContext().getRealPath("/WEB-INF"));
                    }
                  }
              }
            // overwrite with user defined views
            copyScript.copyViews(true);
          } catch (IllegalAccessException e) {
              LOG.error("Illegal access creating resource copier. Save/reload disabled: " + e.getMessage(), e);
          } catch (InstantiationException e) {
              LOG.error("Error instantiating resource copier. Save/reload disabled: " + e.getMessage(), e);
          } catch (CompilationFailedException e) {
               LOG.error("Error compiling resource copier. Save/reload disabled: " + e.getMessage(), e);
          } catch(Exception e) {
             LOG.error("Error loading resource copier. Save/reload disabled: " + e.getMessage(), e);
          }
        }
	if(LOG.isDebugEnabled()) {
	      LOG.debug("Running copy script...");
	}	
        if(copyScript != null) {
            copyScript.copyViews();
            copyScript.copyResourceBundles();
        } 

        
        if(manager == null) {
        	manager = PluginManagerHolder.getPluginManager();
        }
        if(manager != null) {
        	if(LOG.isDebugEnabled())
        		LOG.debug("Checking Plugin manager for changes..");
        	manager.checkForChanges();
        }
        else if(LOG.isDebugEnabled()) {
        	LOG.debug("Plugin manager not found, skipping change check");
        }

        filterChain.doFilter(httpServletRequest,httpServletResponse);
    }

    private void loadServiceClass(Class loadedClass, boolean isNew) {

        GrailsServiceClass serviceClass = application.addServiceClass(loadedClass);
        if(serviceClass.isTransactional() && !isNew) {
            LOG.warn("Cannot reload class ["+loadedClass+"] reloading of transactional service classes is not currently possible. Set class to non-transactional first.");
        }
        else {
            // reload whole context
            if(serviceClass != null) {
                // if its a new taglib, reload app context
                if(isNew) {
                    config.registerService(serviceClass,context);
                }
                else {
                    // swap target source in app context
                    HotSwappableTargetSource targetSource = (HotSwappableTargetSource)context.getBean(serviceClass.getFullName() + "TargetSource");
                    targetSource.swap(serviceClass.newInstance());
                }
            }
        }
    }

    private void loadTagLibClass(Class loadedClass, boolean isNew) {
        GrailsTagLibClass tagLibClass = application.addTagLibClass(loadedClass);
        if(tagLibClass != null) {
            // if its a new taglib, reload app context
            if(isNew) {
            	config.registerTagLibrary(tagLibClass, context);            
            }
            else {
                // swap target source in app context
                HotSwappableTargetSource targetSource = (HotSwappableTargetSource)context.getBean(tagLibClass.getFullName() + "TargetSource");
                targetSource.swap(tagLibClass);
            }
        }
    }

    private void loadDomainClass(Class loadedClass, boolean isNew) throws ClassNotFoundException {
        GrailsDomainClass domainClass = application.addDomainClass(loadedClass);
        Collection loadedDomainClasses = new ArrayList();
        loadedDomainClasses.add( domainClass );

        // go through all domain classes an establish if they are related to this one.
        // and don't already have a reference within the class itself
        GrailsDomainClass[] domainClasses = application.getGrailsDomainClasses();
        for (int i = 0; i < domainClasses.length; i++) {
            GrailsDomainClass grailsDomainClass = domainClasses[i];
            if(!grailsDomainClass.equals(domainClass)) {
                GrailsDomainClassProperty[] properties = grailsDomainClass.getPersistantProperties();
                for (int j = 0; j < properties.length; j++) {
                    GrailsDomainClassProperty property = properties[j];
                    if(property.getType().getName().equals(domainClass.getFullName())) {
                        ResourceMeta rm = (ResourceMeta)resourceMetas.get(grailsDomainClass.getClazz().getName());
                        if(rm != null) {
                            File groovyFile = new File(rm.url.getFile());
                            if(groovyFile.exists()) {
                                groovyFile.setLastModified(System.currentTimeMillis());

                                Class relatedClass = application
                                                        .getClassLoader()
                                                        .loadClass(grailsDomainClass.getClazz().getName(),true,false);
                                loadedDomainClasses.add( application.addDomainClass(relatedClass) );
                            }
                        }
                    }
                }
            }
        }

        if(isNew) {
        	config.registerDomainClass(domainClass,context);        	
        }
        else {
        	config.updateDomainClass(domainClass,context);
        }
        
        for (Iterator i = loadedDomainClasses.iterator(); i.hasNext();) {
            GrailsDomainClass grailsDomainClass = (GrailsDomainClass) i.next();
            config.updateDomainClass(grailsDomainClass,context);
            
            GrailsControllerClass controllerClass = application.getScaffoldingController(grailsDomainClass);
            if(controllerClass != null && controllerClass.isScaffolding()) {
                // generate new views
            	ScaffoldDomain scaffoldDomain = (ScaffoldDomain)context.getBean(grailsDomainClass.getFullName()+"ScaffoldDomain");
            	scaffoldDomain.setPersistentClass(grailsDomainClass.getClazz());
                LOG.info("Re-generating views for scaffold controller ["+controllerClass.getFullName()+"]");
                templateGenerator.generateViews(domainClass,getServletContext().getRealPath("/WEB-INF"));
                // overwrite with user defined views
                copyScript.copyViews(true);
            }

        }
        config.refreshSessionFactory(application,context);
    }

    void loadControllerClass(Class loadedClass, boolean isNew) {
        GrailsControllerClass controllerClass = application.addControllerClass(loadedClass);
        if(controllerClass != null) {
             // if its a new controller re-generate web.xml, reload app context
            if(isNew) {
            	if(copyScript != null) {            		
                    // clean controllers
                    copyScript.cleanControllers();
                    // re-generate web.xml
                    LOG.info("New controller added, re-generating web.xml");
                    copyScript.generateWebXml();
            	}
            }
            else {
                // regenerate controller urlMap
                Properties mappings = new Properties();
                for (int i = 0; i < application.getControllers().length; i++) {
                    GrailsControllerClass simpleController = application.getControllers()[i];
                    for (int x = 0; x < simpleController.getURIs().length; x++) {
                        if(!mappings.containsKey(simpleController.getURIs()[x]))
                            mappings.put(simpleController.getURIs()[x], SimpleGrailsController.APPLICATION_CONTEXT_ID);
                    }
                }
                for (int i = 0; i < application.getPageFlows().length; i++) {
                    GrailsPageFlowClass pageFlow = application.getPageFlows()[i];
                    mappings.put(pageFlow.getUri(), pageFlow.getFullName() + "Controller");
                }

                HotSwappableTargetSource urlMappingsTargetSource = (HotSwappableTargetSource)context.getBean(GrailsUrlHandlerMapping.APPLICATION_CONTEXT_TARGET_SOURCE);

                GrailsUrlHandlerMapping urlMappings = new GrailsUrlHandlerMapping();
                urlMappings.setApplicationContext(context);
                urlMappings.setMappings(mappings);
                String[] interceptorNames = context.getBeanNamesForType(HandlerInterceptor.class);
                String[] webRequestInterceptors = context.getBeanNamesForType( WebRequestInterceptor.class);
                
                HandlerInterceptor[] interceptors = new HandlerInterceptor[interceptorNames.length+webRequestInterceptors.length];
                int j = 0;
                for (int i = 0; i < interceptorNames.length; i++) {
                    String interceptorName = interceptorNames[i];
                    interceptors[i] = (HandlerInterceptor)context.getBean(interceptorName);
                    j = i+1;
                }
                for(int i = 0; i < webRequestInterceptors.length; i++) {
                	j = i+j;
                	interceptors[j] = new WebRequestHandlerInterceptorAdapter( (WebRequestInterceptor) context.getBean(webRequestInterceptors[i]));
                }
                LOG.info("Re-adding " + interceptors.length + " interceptors to mapping");
                urlMappings.setInterceptors(interceptors);
                urlMappings.initApplicationContext();


                urlMappingsTargetSource.swap(urlMappings);


                // swap target source in app context
                HotSwappableTargetSource controllerTargetSource = (HotSwappableTargetSource)context.getBean(controllerClass.getFullName() + "TargetSource");
                controllerTargetSource.swap(controllerClass);
            }
        }
    }

/*    private void reloadApplicationContext() {
        WebApplicationContext parent = (WebApplicationContext)getServletContext().getAttribute(GrailsApplicationAttributes.PARENT_APPLICATION_CONTEXT);
        // construct the SpringConfig for the container managed application
        if(this.application == null)
            this.application = (GrailsApplication) parent.getBean(GrailsApplication.APPLICATION_ID, GrailsApplication.class);

        GrailsRuntimeConfigurator config = new GrailsRuntimeConfigurator(application,parent);
        context = (GrailsWebApplicationContext)config.configure(super.getServletContext());

       getServletContext().setAttribute(GrailsApplicationAttributes.APPLICATION_CONTEXT, context );
       getServletContext().setAttribute(GrailsApplication.APPLICATION_ID, context.getBean(GrailsApplication.APPLICATION_ID) );

        // re-configure scaffolders
        GrailsConfigUtils.configureScaffolders(application,context);
    }*/
}
