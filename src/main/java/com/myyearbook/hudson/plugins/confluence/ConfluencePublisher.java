
package com.myyearbook.hudson.plugins.confluence;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.plugins.confluence.soap.RemoteAttachment;
import hudson.plugins.confluence.soap.RemotePage;
import hudson.plugins.confluence.soap.RemotePageUpdateOptions;
import hudson.plugins.confluence.soap.RemoteSpace;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.DescribableList;
import hudson.util.FormValidation;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import net.sf.json.JSONObject;

import com.myyearbook.hudson.plugins.confluence.wiki.editors.MarkupEditor;
import com.myyearbook.hudson.plugins.confluence.wiki.editors.MarkupEditor.TokenNotFoundException;

import java.io.File;
import java.io.IOException;
import java.net.URLConnection;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class ConfluencePublisher extends Notifier implements Saveable {
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final String siteName;
    private final boolean attachArchivedArtifacts;
    private final String fileSet;

    private final String spaceName;
    private final String pageName;

    private DescribableList<MarkupEditor, Descriptor<MarkupEditor>> editors =
            new DescribableList<MarkupEditor, Descriptor<MarkupEditor>>(this);

    @DataBoundConstructor
    public ConfluencePublisher(String siteName, final String spaceName, final String pageName,
            final boolean attachArchivedArtifacts, final String fileSet,
            final List<MarkupEditor> editorList) throws IOException {

        if (siteName == null) {
            List<ConfluenceSite> sites = getDescriptor().getSites();

            if (sites != null && sites.size() > 0) {
                siteName = sites.get(0).getName();
            }
        }

        this.siteName = siteName;
        this.spaceName = spaceName;
        this.pageName = pageName;
        this.attachArchivedArtifacts = attachArchivedArtifacts;
        this.fileSet = fileSet;

        if (editorList != null) {
            this.editors.addAll(editorList);
        } else {
            this.editors.clear();
        }
    }

    @Exported
    public List<MarkupEditor> getConfiguredEditors() {
        return this.editors.toList();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * @return the fileSet
     */
    public String getFileSet() {
        return fileSet;
    }

    /**
     * @return the pageName
     */
    public String getPageName() {
        return pageName;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    public ConfluenceSite getSite() {
        List<ConfluenceSite> sites = getDescriptor().getSites();

        if (sites == null) {
            return null;
        }

        if (siteName == null && sites.size() > 0) {
            // default
            return sites.get(0);
        }

        for (ConfluenceSite site : sites) {
            if (site.getName().equals(siteName)) {
                return site;
            }
        }
        return null;
    }

    /**
     * @return the siteName
     */
    public String getSiteName() {
        return siteName;
    }

    /**
     * @return the spaceName
     */
    public String getSpaceName() {
        return spaceName;
    }

    protected boolean performAttachments(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener, ConfluenceSession confluence, RemotePage pageData)
            throws IOException, InterruptedException {
        final long pageId = pageData.getId();

        FilePath ws = build.getWorkspace();

        if (ws == null) {
            // Possibly running on a slave that went down
            log(listener, "Workspace is unavailable.");
            return false;
        }

        String attachmentComment = build.getEnvironment(listener).expand(
                "Published from Jenkins build: $BUILD_URL");

        log(listener, "Uploading attachments to Confluence page: " + pageData.getUrl());

        final List<FilePath> files = new ArrayList<FilePath>();

        if (this.attachArchivedArtifacts) {
            final List<FilePath> archived = this.findArtifacts(build.getArtifactsDir());
            log(listener, "Found " + archived.size()
                    + " archived artifact(s) to upload to Confluence...");
            files.addAll(archived);
        }

        final String fileSet = hudson.Util.fixEmptyAndTrim(this.fileSet);

        if (!StringUtils.isEmpty(fileSet)) {
            log(listener, "Evaluating fileset pattern: " + fileSet);

            // Expand environment variables
            final String artifacts = build.getEnvironment(listener).expand(fileSet);
            // Obtain a list of all files that match the pattern
            final FilePath[] workspaceFiles = ws.list(artifacts);

            if (workspaceFiles.length > 0) {
                log(listener, "Found " + workspaceFiles.length
                        + " workspace artifact(s) to upload to Confluence...");

                for (FilePath file : workspaceFiles) {
                    if (!files.contains(file)) {
                        files.add(file);
                    } else {
                        // Don't include the file twice if it's already in the
                        // list
                        log(listener, " - pattern matched an archived artifact: " + file.getName());
                    }
                }
            } else {
                log(listener, "No files matched the pattern '" + fileSet + "'.");
                String msg = null;

                try {
                    msg = ws.validateAntFileMask(artifacts);
                } catch (Exception e) {
                    log(listener, "" + e.getMessage());
                }

                if (msg != null) {
                    log(listener, "" + msg);
                }
            }
        }

        log(listener, "Uploading " + files.size() + " file(s) to Confluence...");

        for (FilePath file : files) {
            final String fileName = file.getName();
            String contentType = URLConnection.guessContentTypeFromName(fileName);

            if (StringUtils.isEmpty(contentType)) {
                // Confluence does not allow an empty content type
                contentType = DEFAULT_CONTENT_TYPE;
            }

            log(listener, " - Uploading file: " + fileName + " (" + contentType + ")");

            try {
                final RemoteAttachment result = confluence.addAttachment(pageId, file, contentType,
                        attachmentComment);
                log(listener, "   done: " + result.getUrl());
            } catch (IOException ioe) {
                listener.error("Unable to upload file...");
                ioe.printStackTrace(listener.getLogger());
            } catch (InterruptedException ie) {
                listener.error("Unable to upload file...");
                ie.printStackTrace(listener.getLogger());
            }
        }
        log(listener, "Done");

        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws RemoteException {

        boolean result = true;
        ConfluenceSite site = getSite();
        ConfluenceSession confluence = site.createSession();

        if (!Result.SUCCESS.equals(build.getResult())) {
            // Don't process for unsuccessful builds
            log(listener, "Build status is not SUCCESS (" + build.getResult().toString() + ").");
            return true;
        }

        final RemotePage pageData = confluence.getPage(spaceName, pageName);

        // Perform attachment uploads
        try {
            result &= this.performAttachments(build, launcher, listener, confluence, pageData);
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        } catch (InterruptedException e) {
            e.printStackTrace(listener.getLogger());
        }

        // Perform wiki replacements
        try {
            result &= this.performWikiReplacements(build, launcher, listener, confluence, pageData);
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        } catch (InterruptedException e) {
            e.printStackTrace(listener.getLogger());
        }

        // Not returning `result`, because this publisher should not
        // fail the job
        return true;
    }

    /**
     * @param build
     * @param launcher
     * @param listener
     * @param confluence
     * @param pageData
     * @return
     * @throws InterruptedException
     * @throws IOException
     */
    protected boolean performWikiReplacements(AbstractBuild<?, ?> build, Launcher launcher,
            BuildListener listener, ConfluenceSession confluence, RemotePage pageData)
            throws IOException, InterruptedException {

        final String editComment = build.getEnvironment(listener).expand(
                "Published from Jenkins build: $BUILD_URL");
        final RemotePageUpdateOptions options = new RemotePageUpdateOptions(false, editComment);

        // Get current content
        String content = pageData.getContent();

        for (MarkupEditor editor : this.editors) {
            log(listener, "Performing wiki edits: " + editor.getDescriptor().getDisplayName());
            try {
                content = editor.performReplacement(build, listener, content);
            } catch (TokenNotFoundException e) {
                log(listener, "ERROR while performing replacement: " + e.getMessage());
            }
        }

        // Now set the replacement content
        pageData.setContent(content);

        confluence.updatePage(pageData, options);

        return true;
    }

    /**
     * Recursively scan a directory, returning all files encountered
     * 
     * @param artifactsDir
     * @return
     */
    private List<FilePath> findArtifacts(File artifactsDir) {
        ArrayList<FilePath> files = new ArrayList<FilePath>();
        for (File f : artifactsDir.listFiles()) {
            if (f.isDirectory()) {
                files.addAll(findArtifacts(f));
            } else if (f.isFile()) {
                files.add(new FilePath(f));
            }
        }
        return files;
    }

    /**
     * Log helper
     * 
     * @param listener
     * @param message
     */
    protected void log(BuildListener listener, String message) {
        listener.getLogger().println("[confluence] " + message);
    }

    /**
     * @return the attachArchivedArtifacts
     */
    public boolean shouldAttachArchivedArtifacts() {
        return attachArchivedArtifacts;
    }

    public void save() throws IOException {
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        private final List<ConfluenceSite> sites = new ArrayList<ConfluenceSite>();

        public DescriptorImpl() {
            super(ConfluencePublisher.class);
            load();
        }

        public List<Descriptor<MarkupEditor>> getEditors() {
            final List<Descriptor<MarkupEditor>> editors = new ArrayList<Descriptor<MarkupEditor>>();

            for (Descriptor<MarkupEditor> editor : MarkupEditor.all()) {
                editors.add(editor);
            }

            return editors;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) {
            this.setSites(req.bindJSONToList(ConfluenceSite.class, formData.get("sites")));
            save();
            return true;
        }

        public FormValidation doPageNameCheck(@QueryParameter final String siteName,
                @QueryParameter final String spaceName, @QueryParameter final String pageName) {
            ConfluenceSite site = this.getSiteByName(siteName);

            if (hudson.Util.fixEmptyAndTrim(spaceName) == null
                    || hudson.Util.fixEmptyAndTrim(pageName) == null) {
                return FormValidation.ok();
            }

            if (site == null) {
                return FormValidation.error("Unknown site:" + siteName);
            }

            try {
                ConfluenceSession confluence = site.createSession();
                RemotePage page = confluence.getPage(spaceName, pageName);
                if (page != null) {
                    return FormValidation.ok("OK: " + page.getTitle());
                }
                return FormValidation.error("Page not found");
            } catch (RemoteException re) {
                return FormValidation.error(re, re.getMessage());
            }
        }

        public FormValidation doSpaceNameCheck(@QueryParameter final String siteName,
                @QueryParameter final String spaceName) {
            ConfluenceSite site = this.getSiteByName(siteName);

            if (hudson.Util.fixEmptyAndTrim(spaceName) == null) {
                return FormValidation.ok();
            }

            if (site == null) {
                return FormValidation.error("Unknown site:" + siteName);
            }

            try {
                ConfluenceSession confluence = site.createSession();
                RemoteSpace space = confluence.getSpace(spaceName);
                if (space != null) {
                    return FormValidation.ok("OK: " + space.getName());
                }
                return FormValidation.error("Space not found");
            } catch (RemoteException re) {
                return FormValidation.error(re, re.getMessage());
            }
        }

        @Override
        public String getDisplayName() {
            return "Publish to Confluence";
        }

        public ConfluenceSite getSiteByName(String siteName) {
            for (ConfluenceSite site : sites) {
                if (site.getName().equals(siteName)) {
                    return site;
                }
            }
            return null;
        }

        public List<ConfluenceSite> getSites() {
            return sites;
        }

        @Override
        public boolean isApplicable(
                @SuppressWarnings("rawtypes") Class<? extends AbstractProject> p) {
            return sites != null && sites.size() > 0;
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData)
                throws hudson.model.Descriptor.FormException {
            return req.bindJSON(ConfluencePublisher.class, formData);
        }

        public void setSites(List<ConfluenceSite> sites) {
            this.sites.clear();
            this.sites.addAll(sites);
        }
    }
}
