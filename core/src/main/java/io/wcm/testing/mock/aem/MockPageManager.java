/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2014 wcm.io
 * %%
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
 * #L%
 */
package io.wcm.testing.mock.aem;

import static com.day.cq.commons.jcr.JcrConstants.JCR_CONTENT;
import static com.day.cq.commons.jcr.JcrConstants.JCR_PRIMARYTYPE;
import static com.day.cq.commons.jcr.JcrConstants.JCR_TITLE;
import static com.day.cq.wcm.api.NameConstants.NT_PAGE;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_MOD;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_MOD_BY;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_PUBLISHED;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_PUBLISHED_BY;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_REPLICATED;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_REPLICATED_BY;
import static com.day.cq.wcm.api.NameConstants.PN_PAGE_LAST_REPLICATION_ACTION;
import static com.day.cq.wcm.api.NameConstants.PN_TEMPLATE;

import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;

import com.day.cq.commons.jcr.JcrUtil;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.api.msm.Blueprint;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Mock implementation of {@link PageManager}
 */
@SuppressWarnings({ "deprecation", "null" })
class MockPageManager extends SlingAdaptable implements PageManager {

  private final ResourceResolver resourceResolver;

  MockPageManager(@NotNull final ResourceResolver resourceResolver) {
    this.resourceResolver = resourceResolver;
  }

  @Override
  public Page create(final String parentPath, final String pageName, final String template, final String title) //NOPMD
      throws WCMException { //NOPMD
    return create(parentPath, pageName, template, title, false);
  }

  @Override
  public Page create(final String parentPath, final String pageName, final String template, final String title, //NOPMD
      final boolean autoSave) throws WCMException { //NOPMD
    Resource parentResource = this.resourceResolver.getResource(parentPath);
    if (parentResource == null) {
      throw new WCMException(String.format("Parent path '%s' does not exist.", parentPath));
    }

    if (StringUtils.isEmpty(pageName) && StringUtils.isEmpty(title)) {
      throw new IllegalArgumentException("Either page name or title must be specified.");
    }

    // derive page name from title if none given
    String childResourceName = pageName;
    if (StringUtils.isEmpty(childResourceName)) {
      childResourceName = JcrUtil.createValidName(title, JcrUtil.HYPHEN_LABEL_CHAR_MAPPING, "_");
    }
    else if (!JcrUtil.isValidName(childResourceName)) {
      throw new IllegalArgumentException("Illegal page name.");
    }

    // use a unique variant of page name if a node with the given name already exists
    try {
      childResourceName = ResourceUtil.createUniqueChildName(parentResource, childResourceName);
    }
    catch (PersistenceException ex) {
      throw new WCMException("Unable to get unique child name.", ex);
    }

    Resource pageResource;
    try {
      // page node
      Map<String, Object> props = new HashMap<>();
      props.put(JCR_PRIMARYTYPE, NT_PAGE);
      pageResource = this.resourceResolver.create(parentResource, childResourceName, props);

      // page content node
      props = new HashMap<>();
      props.put(JCR_PRIMARYTYPE, "cq:PageContent");
      props.put(JCR_TITLE, title);
      props.put(PN_TEMPLATE, template);
      Resource contentResource = this.resourceResolver.create(pageResource, JCR_CONTENT, props);

      // create initial content from template
      Resource templateResource = resourceResolver.getResource(template);
      if (templateResource != null) {
        Template templateInstance = templateResource.adaptTo(Template.class);
        if (templateInstance != null) {
          String initialContentPath = templateInstance.getInitialContentPath();
          Resource initialContentResource = resourceResolver.getResource(initialContentPath);
          if (initialContentResource != null) {
            copyContent(initialContentResource, contentResource, true);
          }
        }
      }

      if (autoSave) {
        this.resourceResolver.commit();
      }
    }
    catch (PersistenceException ex) {
      throw new WCMException("Creating page failed at :" + parentPath + "/" + childResourceName + " failed.", ex);
    }

    return pageResource.adaptTo(Page.class);
  }

  @SuppressFBWarnings("STYLE")
  private void copyContent(Resource source, Resource target, boolean skipPrimaryType) throws PersistenceException {
    ValueMap sourceProps = source.adaptTo(ValueMap.class);
    ModifiableValueMap targetProps = target.adaptTo(ModifiableValueMap.class);
    Node node = target.adaptTo(Node.class);

    for (Map.Entry<String, Object> entry : sourceProps.entrySet()) {
      if (skipPrimaryType && StringUtils.equals(entry.getKey(), JCR_PRIMARYTYPE)) {
        continue;
      }

      // If JCR repository is used: skip protected properties
      if (node != null) {
        try {
          Property property = node.getProperty(entry.getKey());
          if (property.getDefinition().isProtected()) {
            continue;
          }
        }
        catch (RepositoryException ex) {
          // ignore
        }
      }

      targetProps.put(entry.getKey(), entry.getValue());
    }
    copyChildren(source, target);
  }

  private void copyChildren(Resource source, Resource target) throws PersistenceException {
    for (Resource sourceChild : source.getChildren()) {
      Resource targetChild = resourceResolver.create(target, sourceChild.getName(), sourceChild.adaptTo(ValueMap.class));
      copyChildren(sourceChild, targetChild);
    }
  }

  @Override
  @SuppressFBWarnings("STYLE")
  public void delete(final Page page, final boolean shallow) throws WCMException {
    delete(page.adaptTo(Resource.class), shallow);
  }

  @Override
  @SuppressFBWarnings("STYLE")
  public void delete(final Page page, final boolean shallow, final boolean autoSave) throws WCMException {
    delete(page.adaptTo(Resource.class), shallow, autoSave);
  }

  @Override
  public void delete(final Resource resource, final boolean shallow) throws WCMException {
    delete(resource, shallow, false);
  }

  @Override
  public void delete(final Resource resource, final boolean shallow, final boolean autoSave) throws WCMException {
    try {
      if (shallow) {
        Resource contentResource = resource.getChild(JCR_CONTENT);
        if (contentResource != null) {
          this.resourceResolver.delete(contentResource);
        }
      }
      else {
        this.resourceResolver.delete(resource);
      }

      if (autoSave) {
        this.resourceResolver.commit();
      }
    }
    catch (PersistenceException ex) {
      throw new WCMException("Deleting resource at " + resource.getPath() + " failed.", ex);
    }
  }

  @Override
  public Page getContainingPage(final Resource resource) {
    if (resource == null) {
      return null;
    }
    Resource pageResource = resource;
    while (pageResource != null) {
      Page page = pageResource.adaptTo(Page.class);
      if (page != null) {
        return page;
      }
      pageResource = pageResource.getParent();
    }
    return null;
  }

  @Override
  public Page getContainingPage(final String path) {
    Resource resource = this.resourceResolver.getResource(path);
    return getContainingPage(resource);
  }

  @Override
  public Page getPage(final String path) {
    Resource resource = this.resourceResolver.getResource(path);
    if (resource != null) {
      return resource.adaptTo(Page.class);
    }
    return null;
  }

  @Override
  public Template getTemplate(final String templatePath) {
    Resource resource = this.resourceResolver.getResource(templatePath);
    if (resource != null) {
      return resource.adaptTo(Template.class);
    }
    else {
      return null;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <AdapterType> AdapterType adaptTo(final Class<AdapterType> type) {
    if (type == ResourceResolver.class) {
      return (AdapterType)this.resourceResolver;
    }
    return super.adaptTo(type);
  }

  @Override
  public void touch(final Node page, final boolean shallow, final Calendar now, final boolean clearRepl) throws WCMException {
    if (!shallow) {
      throw new UnsupportedOperationException("Only shallow touch supported");
    }
    try {
      Resource pageContent = resourceResolver.getResource(page.getPath() + '/' + JCR_CONTENT);
      if (pageContent != null) {
        ModifiableValueMap properties = pageContent.adaptTo(ModifiableValueMap.class);
        if (now != null) {
          properties.put(PN_PAGE_LAST_MOD, now);
          properties.put(PN_PAGE_LAST_MOD_BY, resourceResolver.getUserID());
        }
        if (clearRepl) {
          properties.remove(PN_PAGE_LAST_REPLICATED);
          properties.remove(PN_PAGE_LAST_REPLICATED_BY);
          properties.remove(PN_PAGE_LAST_REPLICATION_ACTION);
          properties.remove(PN_PAGE_LAST_PUBLISHED);
          properties.remove(PN_PAGE_LAST_PUBLISHED_BY);
        }
      }
    }
    catch (RepositoryException ex) {
      throw new WCMException(ex);
    }
  }


  // --- unsupported operations ---

  @Override
  public Page move(final Page page, final String destination, final String beforeName, final boolean shallow,
      final boolean resolveConflict, final String[] adjustRefs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Resource move(final Resource resource, final String destination, final String beforeName,
      final boolean shallow, final boolean resolveConflict, final String[] adjustRefs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page copy(final Page page, final String destination, final String beforeName, final boolean shallow,
      final boolean resolveConflict) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page copy(final Page page, final String destination, final String beforeName, final boolean shallow,
      final boolean resolveConflict, final boolean autoSave) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Resource copy(final Resource resource, final String destination, final String beforeName,
      final boolean shallow, final boolean resolveConflict) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Resource copy(final Resource resource, final String destination, final String beforeName,
      final boolean shallow, final boolean resolveConflict, final boolean autoSave) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void order(final Page page, final String beforeName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void order(final Page page, final String beforeName, final boolean autoSave) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void order(final Resource resource, final String beforeName) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void order(final Resource resource, final String beforeName, final boolean autoSave) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Template> getTemplates(final String parentPath) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Blueprint> getBlueprints(final String parentPath) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Revision createRevision(final Page page) throws WCMException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Revision createRevision(final Page page, final String label, final String comment) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Revision> getRevisions(final String path, final Calendar cal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Revision> getRevisions(final String path, final Calendar cal, final boolean includeNoLocal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Revision> getChildRevisions(final String parentPath, final Calendar cal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Revision> getChildRevisions(final String parentPath, final Calendar cal, final boolean includeNoLocal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<Revision> getChildRevisions(final String parentPath, final String treeRoot, final Calendar cal) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page restore(final String path, final String revisionId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page restoreTree(final String path, final Calendar date) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page restoreTree(final String path, final Calendar date, final boolean preserveNV) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Page move(Page page, String destination, String beforeName, boolean shallow,
      boolean resolveConflict, String[] adjustRefs, String[] publishRefs) throws WCMException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Resource move(Resource resource, String destination, String beforeName, boolean shallow,
      boolean resolveConflict, String[] adjustRefs, String[] publishRefs) throws WCMException {
    throw new UnsupportedOperationException();
  }

  // AEM 6.5.18
  @SuppressWarnings("unused")
  public Resource move(Resource resource, String destination, String beforeName, boolean shallow,
      boolean resolveConflict, String[] adjustRefs, String[] publishRefs, String arg7) throws WCMException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Resource copy(CopyOptions options) throws WCMException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void delete(Resource arg0, boolean arg1, boolean arg2, boolean arg3) throws WCMException {
    throw new UnsupportedOperationException();
  }

  // AEMaaCS 2023.9.13665.20230927T063259Z-230800
  @SuppressWarnings("unused")
  public Resource override(CopyOptions options) throws WCMException {
    throw new UnsupportedOperationException();
  }

}
