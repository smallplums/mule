/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.registry;

import org.mule.api.MuleContext;
import org.mule.api.MuleException;
import org.mule.api.NameableObject;
import org.mule.api.agent.Agent;
import org.mule.api.config.MuleProperties;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.endpoint.EndpointBuilder;
import org.mule.api.endpoint.EndpointFactory;
import org.mule.api.endpoint.ImmutableEndpoint;
import org.mule.api.lifecycle.Disposable;
import org.mule.api.lifecycle.Initialisable;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.api.lifecycle.LifecycleException;
import org.mule.api.model.Model;
import org.mule.api.registry.AbstractServiceDescriptor;
import org.mule.api.registry.MuleRegistry;
import org.mule.api.registry.RegistrationException;
import org.mule.api.registry.ResolverException;
import org.mule.api.registry.ServiceDescriptor;
import org.mule.api.registry.ServiceDescriptorFactory;
import org.mule.api.registry.ServiceException;
import org.mule.api.registry.ServiceType;
import org.mule.api.registry.TransformerResolver;
import org.mule.api.schedule.Scheduler;
import org.mule.api.service.Service;
import org.mule.api.transformer.Converter;
import org.mule.api.transformer.DataType;
import org.mule.api.transformer.Transformer;
import org.mule.api.transformer.TransformerException;
import org.mule.api.transport.Connector;
import org.mule.config.i18n.CoreMessages;
import org.mule.transformer.types.SimpleDataType;
import org.mule.util.Predicate;
import org.mule.util.SpiUtils;
import org.mule.util.StringUtils;
import org.mule.util.UUID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Adds lookup/register/unregister methods for Mule-specific entities to the standard
 * Registry interface.
 */
public class MuleRegistryHelper implements MuleRegistry
{
    protected transient Log logger = LogFactory.getLog(MuleRegistryHelper.class);

    /**
     * A reference to Mule's internal registry
     */
    private DefaultRegistryBroker registry;

    /**
     * We cache transformer searches so that we only search once
     */
    protected ConcurrentHashMap/*<String, Transformer>*/ exactTransformerCache = new ConcurrentHashMap/*<String, Transformer>*/(8);
    protected ConcurrentHashMap/*Map<String, List<Transformer>>*/ transformerListCache = new ConcurrentHashMap/*<String, List<Transformer>>*/(8);

    private MuleContext muleContext;

    public MuleRegistryHelper(DefaultRegistryBroker registry, MuleContext muleContext)
    {
        this.registry = registry;
        this.muleContext = muleContext;
    }

    /**
     * {@inheritDoc}
     */
    public void initialise() throws InitialisationException
    {
        //no-op

        //This is called when the MuleContext starts up, and should only do initialisation for any state on this class, the lifecycle
        //for the registries will be handled by the LifecycleManager on the registry that this class wraps
    }

    /**
     * {@inheritDoc}
     */
    public void dispose()
    {
        transformerListCache.clear();
        exactTransformerCache.clear();
    }

    public void fireLifecycle(String phase) throws LifecycleException
    {
        if (Initialisable.PHASE_NAME.equals(phase))
        {
            registry.initialise();
        }
        else if (Disposable.PHASE_NAME.equals(phase))
        {
            registry.dispose();
        }
        else
        {
            registry.fireLifecycle(phase);
        }
    }

    /**
     * {@inheritDoc}
     */
    public Connector lookupConnector(String name)
    {
        return (Connector) registry.lookupObject(name);
    }

    /**
     * Removed this method from {@link Registry} API as it should only be used
     * internally and may confuse users. The {@link EndpointFactory} should be used
     * for creating endpoints.<br/><br/> Looks up an returns endpoints registered in the
     * registry by their identifier (currently endpoint name)<br/><br/ <b>NOTE:
     * This method does not create new endpoint instances, but rather returns
     * existing endpoint instances that have been registered. This lookup method
     * should be avoided and the intelligent, role specific endpoint lookup methods
     * should be used instead.<br/><br/>
     *
     * @param name the idendtifer/name used to register endpoint in registry
     * @return foo
     */
    /*public ImmutableEndpoint lookupEndpoint(String name)
    {
        Object obj = registry.lookupObject(name);
        if (obj instanceof ImmutableEndpoint)
        {
            return (ImmutableEndpoint) obj;
        }
        else
        {
            logger.debug("No endpoint with the name: "
                    + name
                    + "found.  If "
                    + name
                    + " is a global endpoint you should use the EndpointFactory to create endpoint instances from global endpoints.");
            return null;
        }
    }*/

    /**
     * {@inheritDoc}
     */
    public EndpointBuilder lookupEndpointBuilder(String name)
    {
        Object o = registry.lookupObject(name);
        if (o instanceof EndpointBuilder)
        {
            logger.debug("Global endpoint EndpointBuilder for name: " + name + " found");
            return (EndpointBuilder) o;
        }
        else
        {
            logger.debug("No endpoint builder with the name: " + name + " found.");
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public EndpointFactory lookupEndpointFactory()
    {
        return (EndpointFactory) registry.lookupObject(MuleProperties.OBJECT_MULE_ENDPOINT_FACTORY);
    }

    /**
     * {@inheritDoc}
     */
    public Transformer lookupTransformer(String name)
    {
        return (Transformer) registry.lookupObject(name);
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #lookupTransformer(org.mule.api.transformer.DataType, org.mule.api.transformer.DataType)} instead.  This
     *             method should only be used internally to discover transformers, typically a user does not need ot do this
     *             directly
     */
    @Deprecated
    public Transformer lookupTransformer(Class inputType, Class outputType) throws TransformerException
    {
        return lookupTransformer(new SimpleDataType(inputType), new SimpleDataType(outputType));
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated use {@link #lookupTransformer(org.mule.api.transformer.DataType, org.mule.api.transformer.DataType)} instead.  This
     *             method should only be used internally to discover transformers, typically a user does not need ot do this
     *             directly
     */
    @Deprecated
    public List<Transformer> lookupTransformers(Class input, Class output)
    {
        return lookupTransformers(new SimpleDataType(input), new SimpleDataType(output));
    }

    /**
     * {@inheritDoc}
     */
    public Transformer lookupTransformer(DataType source, DataType result) throws TransformerException
    {
        final String dataTypePairHash = getDataTypeSourceResultPairHash(source, result);
        Transformer cachedTransformer = (Transformer) exactTransformerCache.get(dataTypePairHash);
        if (cachedTransformer != null)
        {
            return cachedTransformer;
        }

        Transformer trans = resolveTransformer(source, result);

        if (trans != null)
        {
            Transformer concurrentlyAddedTransformer = (Transformer) exactTransformerCache.putIfAbsent(
                    dataTypePairHash, trans);
            if (concurrentlyAddedTransformer != null)
            {
                return concurrentlyAddedTransformer;
            }
            else
            {
                return trans;
            }
        }
        else
        {
            throw new TransformerException(CoreMessages.noTransformerFoundForMessage(source, result));
        }
    }

    protected Transformer resolveTransformer(DataType source, DataType result) throws TransformerException
    {
        List<TransformerResolver> resolvers = (List<TransformerResolver>) lookupObjects(TransformerResolver.class);
        Collections.sort(resolvers, new TransformerResolverComparator());

        for (TransformerResolver resolver : resolvers)
        {
            try
            {
                Transformer trans = resolver.resolve(source, result);
                if (trans != null)
                {
                    return trans;
                }
            }
            catch (ResolverException e)
            {
                throw new TransformerException(CoreMessages.noTransformerFoundForMessage(source, result), e);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public List<Transformer> lookupTransformers(DataType source, DataType result)
    {
        final String dataTypePairHash = getDataTypeSourceResultPairHash(source, result);

        List<Transformer> results = (List<Transformer>) transformerListCache.get(dataTypePairHash);
        if (results != null)
        {
            return results;
        }

        results = new ArrayList<Transformer>(2);
        Collection<Transformer> transformers = registry.lookupObjects(Transformer.class);
        for (Transformer t : transformers)
        {
            // The transformer must have the DiscoveryTransformer interface if we are
            // going to
            // find it here
            if (!(t instanceof Converter))
            {
                continue;
            }
            DataType dt = t.getReturnDataType();
            if (result.isCompatibleWith(dt) && t.isSourceDataTypeSupported(source))
            {
                results.add(t);
            }
        }

        List<Transformer> concurrentlyAddedTransformers = (List<Transformer>) transformerListCache.putIfAbsent(
                dataTypePairHash, results);
        if (concurrentlyAddedTransformers != null)
        {
            return concurrentlyAddedTransformers;
        }
        else
        {
            return results;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Model lookupModel(String name)
    {
        return (Model) registry.lookupObject(name);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Model lookupSystemModel()
    {
        return lookupModel(MuleProperties.OBJECT_SYSTEM_MODEL);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public Collection<Model> getModels()
    {
        return registry.lookupObjects(Model.class);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<Connector> getConnectors()
    {
        return registry.lookupObjects(Connector.class);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<Agent> getAgents()
    {
        return registry.lookupObjects(Agent.class);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<ImmutableEndpoint> getEndpoints()
    {
        return registry.lookupObjects(ImmutableEndpoint.class);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<Transformer> getTransformers()
    {
        return registry.lookupObjects(Transformer.class);
    }

    /**
     * {@inheritDoc}
     */
    public Agent lookupAgent(String name)
    {
        return (Agent) registry.lookupObject(name);
    }

    /**
     * {@inheritDoc}
     */
    public Service lookupService(String name)
    {
        return (Service) registry.lookupObject(name);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<Service> lookupServices()
    {
        return lookupObjects(Service.class);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<Service> lookupServices(String model)
    {
        Collection<Service> services = lookupServices();
        List<Service> modelServices = new ArrayList<Service>();
        Iterator it = services.iterator();
        Service service;
        while (it.hasNext())
        {
            service = (Service) it.next();
            if (model.equals(service.getModel().getName()))
            {
                modelServices.add(service);
            }
        }
        return modelServices;
    }

    /**
     * {@inheritDoc}
     */
    public FlowConstruct lookupFlowConstruct(String name)
    {
        return (FlowConstruct) registry.lookupObject(name);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<FlowConstruct> lookupFlowConstructs()
    {
        return lookupObjects(FlowConstruct.class);
    }

    /**
     * {@inheritDoc}
     */
    public final void registerTransformer(Transformer transformer) throws MuleException
    {
        registry.registerObject(getName(transformer), transformer, Transformer.class);
        notifyTransformerResolvers(transformer, TransformerResolver.RegistryAction.ADDED);
    }

    public void notifyTransformerResolvers(Transformer t, TransformerResolver.RegistryAction action)
    {
        if (t instanceof Converter)
        {
            Collection<TransformerResolver> resolvers = lookupObjects(TransformerResolver.class);
            for (TransformerResolver resolver : resolvers)
            {
                resolver.transformerChange(t, action);
            }
            transformerListCache.clear();
            exactTransformerCache.clear();
        }
    }

    /**
     * Looks up the service descriptor from a singleton cache and creates a new one if not found.
     */
    public ServiceDescriptor lookupServiceDescriptor(ServiceType type, String name, Properties overrides) throws ServiceException
    {
        String key = new AbstractServiceDescriptor.Key(name, overrides).getKey();
        // TODO If we want these descriptors loaded form Spring we need to change the key mechanism
        // and the scope, and then deal with circular reference issues.
        ServiceDescriptor sd = (ServiceDescriptor) registry.lookupObject(key);

        synchronized (this)
        {
            if (sd == null)
            {
                sd = createServiceDescriptor(type, name, overrides);
                try
                {
                    registry.registerObject(key, sd, ServiceDescriptor.class);
                }
                catch (RegistrationException e)
                {
                    throw new ServiceException(e.getI18nMessage(), e);
                }
            }
        }
        return sd;
    }

    protected ServiceDescriptor createServiceDescriptor(ServiceType type, String name, Properties overrides) throws ServiceException
    {
        //Stripe off and use the meta-scheme if present
        String scheme = name;
        if (name.contains(":"))
        {
            scheme = name.substring(0, name.indexOf(":"));
        }

        Properties props = SpiUtils.findServiceDescriptor(type, scheme);
        if (props == null)
        {
            throw new ServiceException(CoreMessages.failedToLoad(type + " " + scheme));
        }

        return ServiceDescriptorFactory.create(type, name, props, overrides, muleContext,
                                               muleContext.getExecutionClassLoader());
    }

    /**
     * {@inheritDoc}
     */
    public void registerAgent(Agent agent) throws MuleException
    {
        registry.registerObject(getName(agent), agent, Agent.class);
    }

    /**
     * {@inheritDoc}
     */
    public void registerConnector(Connector connector) throws MuleException
    {
        registry.registerObject(getName(connector), connector, Connector.class);
    }

    /**
     * {@inheritDoc}
     */
    public void registerEndpoint(ImmutableEndpoint endpoint) throws MuleException
    {
        registry.registerObject(getName(endpoint), endpoint, ImmutableEndpoint.class);
    }

    /**
     * {@inheritDoc}
     */
    public void registerEndpointBuilder(String name, EndpointBuilder builder) throws MuleException
    {
        registry.registerObject(name, builder, EndpointBuilder.class);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public void registerModel(Model model) throws MuleException
    {
        registry.registerObject(getName(model), model, Model.class);
    }

    /**
     * {@inheritDoc}
     */
    public void registerService(Service service) throws MuleException
    {
        registry.registerObject(getName(service), service, Service.class);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterService(String serviceName) throws MuleException
    {
        registry.unregisterObject(serviceName, Service.class);
    }

    /**
     * {@inheritDoc}
     */
    public void registerFlowConstruct(FlowConstruct flowConstruct) throws MuleException
    {
        registry.registerObject(getName(flowConstruct), flowConstruct, FlowConstruct.class);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterFlowConstruct(String flowConstructName) throws MuleException
    {
        registry.unregisterObject(flowConstructName, FlowConstruct.class);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterAgent(String agentName) throws MuleException
    {
        registry.unregisterObject(agentName, Agent.class);
    }

    @Override
    public void registerScheduler(Scheduler scheduler) throws MuleException
    {
        registry.registerObject(scheduler.getName(), scheduler);
    }

    @Override
    public void unregisterScheduler(Scheduler scheduler) throws MuleException
    {
        registry.unregisterObject(scheduler.getName(), scheduler);
    }

    @Override
    public Collection<Scheduler> lookupScheduler(Predicate<String> schedulerNamePredicate)
    {
        Collection<Scheduler> schedulers = new ArrayList<Scheduler>();
        Map<String, Scheduler> registeredSchedulers = lookupByType(Scheduler.class);
        for (Scheduler registeredScheduler : registeredSchedulers.values())
        {
            if (schedulerNamePredicate.evaluate(registeredScheduler.getName()))
            {
                schedulers.add(registeredScheduler);
            }
        }

        return schedulers;
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterConnector(String connectorName) throws MuleException
    {
        registry.unregisterObject(connectorName, Connector.class);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterEndpoint(String endpointName) throws MuleException
    {
        registry.unregisterObject(endpointName, ImmutableEndpoint.class);
    }

    /**
     * {@inheritDoc}
     */
    @Deprecated
    public void unregisterModel(String modelName) throws MuleException
    {
        registry.unregisterObject(modelName, Model.class);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterTransformer(String transformerName) throws MuleException
    {
        Transformer transformer = lookupTransformer(transformerName);
        notifyTransformerResolvers(transformer, TransformerResolver.RegistryAction.REMOVED);
        registry.unregisterObject(transformerName, Transformer.class);

    }

    /**
     * {@inheritDoc}
     */
    public Object applyProcessorsAndLifecycle(Object object) throws MuleException
    {
        object = applyProcessors(object);
        object = applyLifecycle(object);
        return object;
    }

    /**
     * {@inheritDoc}
     */
    public Object applyProcessors(Object object) throws MuleException
    {
        return registry.getTransientRegistry().applyProcessors(object, null);
    }

    /**
     * {@inheritDoc}
     */
    public Object applyProcessors(Object object, int flags) throws MuleException
    {
        return registry.getTransientRegistry().applyProcessors(object, flags);
    }

    /**
     * {@inheritDoc}
     */
    public Object applyLifecycle(Object object) throws MuleException
    {
        return registry.getTransientRegistry().applyLifecycle(object);
    }

    public Object applyLifecycle(Object object, String phase) throws MuleException
    {
        return registry.getTransientRegistry().applyLifecycle(object, phase);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Delegate to internal registry
    ////////////////////////////////////////////////////////////////////////////

    /**
     * {@inheritDoc}
     */
    public <T> T lookupObject(Class<T> type) throws RegistrationException
    {
        return registry.lookupObject(type);
    }

    @SuppressWarnings("unchecked")
    public <T> T lookupObject(String key)
    {
        return (T) registry.lookupObject(key);
    }

    /**
     * {@inheritDoc}
     */
    public <T> Collection<T> lookupObjects(Class<T> type)
    {
        return registry.lookupObjects(type);
    }

    /**
     * {@inheritDoc}
     */
    public <T> Collection<T> lookupObjectsForLifecycle(Class<T> type)
    {
        return registry.lookupObjectsForLifecycle(type);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key)
    {
        return (T) registry.get(key);
    }

    public <T> Map<String, T> lookupByType(Class<T> type)
    {
        return registry.lookupByType(type);
    }

    /**
     * {@inheritDoc}
     */
    public void registerObject(String key, Object value, Object metadata) throws RegistrationException
    {
        registry.registerObject(key, value, metadata);
    }

    /**
     * {@inheritDoc}
     */
    public void registerObject(String key, Object value) throws RegistrationException
    {
        registry.registerObject(key, value);
    }

    /**
     * {@inheritDoc}
     */
    public void registerObjects(Map objects) throws RegistrationException
    {
        registry.registerObjects(objects);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterObject(String key, Object metadata) throws RegistrationException
    {
        registry.unregisterObject(key, metadata);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterObject(String key) throws RegistrationException
    {
        registry.unregisterObject(key);
    }

    /**
     * Returns the name for the object passed in.  If the object implements {@link org.mule.api.NameableObject}, then
     * {@link org.mule.api.NameableObject#getName()} will be returned, otherwise a name is generated using the class name
     * and a generated UUID.
     *
     * @param obj the object to inspect
     * @return the name for this object
     */
    protected String getName(Object obj)
    {
        String name = null;
        if (obj instanceof NameableObject)
        {
            name = ((NameableObject) obj).getName();
        }
        else if (obj instanceof FlowConstruct)
        {
            name = ((FlowConstruct) obj).getName();
        }
        if (StringUtils.isBlank(name))
        {
            name = obj.getClass().getName() + ":" + UUID.getUUID();
        }
        return name;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Registry Metadata
    ////////////////////////////////////////////////////////////////////////////

    /**
     * {@inheritDoc}
     */
    public String getRegistryId()
    {
        return this.toString();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isReadOnly()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isRemote()
    {
        return false;
    }

    private String getDataTypeSourceResultPairHash(DataType<?> source, DataType<?> result)
    {
        return source.getClass().getName() + source.hashCode() + ":" + result.getClass().getName()
               + result.hashCode();
    }

    private class TransformerResolverComparator implements Comparator<TransformerResolver>
    {

        public int compare(TransformerResolver transformerResolver, TransformerResolver transformerResolver1)
        {
            if (transformerResolver.getClass().equals(TypeBasedTransformerResolver.class))
            {
                return 1;
            }

            if (transformerResolver1.getClass().equals(TypeBasedTransformerResolver.class))
            {
                return -1;
            }
            return 0;
        }
    }
}


