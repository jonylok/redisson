/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.spring.support;

import java.util.List;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.core.Conventions;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

/**
 *
 * @author Rui Gu (https://github.com/jackygurui)
 */
public final class RedissonDefinitionParser 
        implements BeanDefinitionParser {

    public static final String ID_ATTRIBUTE = "id";
    public static final String NAME_ATTRIBUTE = "name";
    private static final String REF_SUFFIX = "-ref";
    private static final String REDISSON_REF = "redisson-ref";
    
    static enum ConfigType {
        singleServer,
        sentinelServers,
        replicatedServers,
        masterSlaveServers,
        clusterServers;

        public static boolean contains(String type) {
            try {
                valueOf(type);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    static enum AddressType {
        slaveAddress,
        sentinelAddress,
        nodeAddress;

        public static boolean contains(String type) {
            try {
                valueOf(type);
                return true;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }
    
    private final RedissonNamespaceParserSupport helper;

    RedissonDefinitionParser(RedissonNamespaceParserSupport helper) {
        this.helper = helper;
    }
    
    private void parseChildElements(Element element, String parentId, String redissonRef, ParserContext parserContext) {
        if (element.hasChildNodes()) {
            CompositeComponentDefinition compositeDef
                    = new CompositeComponentDefinition(parentId,
                            parserContext.extractSource(element));
            parserContext.pushContainingComponent(compositeDef);
            List<Element> childElts = DomUtils.getChildElements(element);
            for (Element elt : childElts) {
                if(BeanDefinitionParserDelegate
                        .QUALIFIER_ELEMENT.equals(elt.getLocalName())) {
                    continue;//parsed elsewhere
                }
                String localName = parserContext.getDelegate().getLocalName(elt);
                localName = Conventions.attributeNameToPropertyName(localName);
                if (ConfigType.contains(localName)) {
                    parseConfigTypes(elt, localName, parserContext);
                } else if (AddressType.contains(localName)) {
                    parseAddressTypes(elt, localName, parserContext);
                } else if (helper.isRedissonNS(elt)) {
                    elt.setAttribute(REDISSON_REF, redissonRef);
                    parserContext.getDelegate().parseCustomElement(elt);
                }
            }
            parserContext.popContainingComponent();
        }
    }
    
    private void parseConfigTypes(Element element, String configType, ParserContext parserContext) {
        BeanDefinitionBuilder builder
                = helper.createBeanDefinitionBuilder(element,
                        parserContext, null);
        //Use factory method on the Config bean
        AbstractBeanDefinition bd = builder.getRawBeanDefinition();
        bd.setFactoryMethodName("use" + StringUtils.capitalize(configType));
        bd.setFactoryBeanName(parserContext.getContainingComponent().getName());
        String id = parserContext.getReaderContext().generateBeanName(bd);
        helper.registerBeanDefinition(builder, id,
                helper.parseAliase(element), parserContext);
        parseAttributes(element, parserContext, builder);
        parseChildElements(element, id, null, parserContext);
        parserContext.getDelegate().parseQualifierElements(element, bd);
    }
    
    private void parseAddressTypes(Element element, String addressType, ParserContext parserContext) {
        helper.invoker(element,
                parserContext.getContainingComponent().getName(),
                "add" + StringUtils.capitalize(addressType),
                new String[]{element.getAttribute("value")},
                parserContext);
    }
    
    private void parseAttributes(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
        NamedNodeMap attributes = element.getAttributes();
        for (int x = 0; x < attributes.getLength(); x++) {
            Attr attribute = (Attr) attributes.item(x);
            if (helper.isEligibleAttribute(attribute)) {
                String propertyName
                        = attribute.getLocalName().endsWith(REF_SUFFIX)
                                ? attribute.getLocalName()
                                        .substring(0, attribute.getLocalName()
                                                .length() - REF_SUFFIX.length())
                                : attribute.getLocalName();
                propertyName = Conventions
                        .attributeNameToPropertyName(propertyName);
                Assert.state(StringUtils.hasText(propertyName),
                        "Illegal property name returned from"
                                + " 'extractPropertyName(String)': cannot be"
                                + " null or empty.");
                if (attribute.getLocalName().endsWith(REF_SUFFIX)) {
                    builder.addPropertyReference(propertyName,
                            attribute.getValue());
                } else {
                    String value = attribute.getValue();
                    String localName = parserContext.getDelegate()
                            .getLocalName(element);
                    localName = Conventions
                            .attributeNameToPropertyName(localName);
                    if ("masterAddress".equals(propertyName)
                            && ConfigType.masterSlaveServers.name()
                                    .equals(localName)) {
                        value = "redis://" + value;
                    }
                    builder.addPropertyValue(propertyName, value);
                }
            }
        }
    }
    
    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {        
        //Sort out the Config Class
        BeanDefinitionBuilder configBuilder 
                = helper.createBeanDefinitionBuilder(element, parserContext,
                        Config.class);
        String configId = helper.getId(null, configBuilder, parserContext);
        parseAttributes(element, parserContext, configBuilder);
        helper.registerBeanDefinition(configBuilder, configId,
                null, parserContext);
        
        //Do the main Redisson bean
        BeanDefinitionBuilder builder 
                = helper.createBeanDefinitionBuilder(element, parserContext,
                        Redisson.class);
        builder.setFactoryMethod("create");
        builder.setDestroyMethodName("shutdown");
        builder.addConstructorArgReference(configId);
        parserContext.getDelegate().parseQualifierElements(element,
                builder.getRawBeanDefinition());
        String id = helper.getId(element, builder, parserContext);
        parseAttributes(element, parserContext, configBuilder);
        //Sort out all the nested elements
        parseChildElements(element, configId, id, parserContext);
        
        helper.registerBeanDefinition(builder, id,
                helper.parseAliase(element), parserContext);
        return builder.getBeanDefinition();
    }
}