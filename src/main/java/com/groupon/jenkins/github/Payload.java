/*
The MIT License (MIT)

Copyright (c) 2014, Groupon, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */
package com.groupon.jenkins.github;

import hudson.model.Cause;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.github.GHRepository;

import com.groupon.jenkins.dynamic.build.cause.GitHubPullRequestCause;
import com.groupon.jenkins.dynamic.build.cause.GitHubPushCause;
import com.groupon.jenkins.dynamic.build.cause.GithubLogEntry;
import com.groupon.jenkins.github.services.GithubRepositoryService;

public class Payload {

	private final JSONObject repository;
	private final JSONObject payloadJson;

	public Payload(String payload) {
		this.payloadJson = JSONObject.fromObject(payload);
		this.repository = payloadJson.getJSONObject("repository");
	}

	public GHRepository getProject() {
		String url = repository.getString("url").replace("/api/v3/repos", "");
		return getGithubRepository(url);
	}

	protected GHRepository getGithubRepository(String url) {
		return new GithubRepositoryService(url).getGithubRepository();
	}

	public boolean isPullRequest() {
		return payloadJson.containsKey("pull_request");
	}

	public Cause getCause() {
		if (isPullRequest()) {
			JSONObject pullRequest = getPullRequest();
			final String label = pullRequest.getJSONObject("head").getString("label");
			String number = pullRequest.getString("number");
			return new GitHubPullRequestCause(this, getSha(), label, number);

		} else {
			final String pusherName = payloadJson.getJSONObject("pusher").getString("name");
			final String email = payloadJson.getJSONObject("pusher").getString("email");
			return new GitHubPushCause(this, getSha(), pusherName, email);
		}
	}

	public String getSha() {
		if (isPullRequest()) {
			return getPullRequest().getJSONObject("head").getString("sha");
		} else {
			return payloadJson.getString("after");
		}
	}

	private JSONObject getPullRequest() {
		return payloadJson.getJSONObject("pull_request");
	}

	public String getBranch() {
		if (isPullRequest()) {
			return "pr/" + getPullRequestNumber() + "/head";
		} else {
			return payloadJson.getString("ref").replaceAll("refs/heads/", "");
		}
	}

	public String getRefSpec() {
		return isPullRequest() ? "+refs/pull/*:refs/remotes/origin/pr/*" : "+refs/heads/*:refs/remotes/origin/*";
	}

	public boolean needsBuild() {
		if (payloadJson.has("ref") && payloadJson.getString("ref").startsWith("refs/tags/")) {
			return false;
		}
		if (isPullRequest()) {
			return !isPullRequestClosed() && !isPullRequestFromWithinSameRepo();
		} else {
			return !payloadJson.getBoolean("deleted");
		}
	}

	private boolean isPullRequestClosed() {
		return "closed".equals(getPullRequest().getString("state"));
	}

	private boolean isPullRequestFromWithinSameRepo() {
		String headRepoUrl = getPullRequest().getJSONObject("head").getJSONObject("repo").getString("ssh_url");
		String pullRequestRepoUrl = getPullRequest().getJSONObject("base").getJSONObject("repo").getString("ssh_url");
		return headRepoUrl.equals(pullRequestRepoUrl);
	}

	public String getBuildDescription() {
		String shortSha = getSha().substring(0, 7);
		return String.format("<b>%s</b> (<a href=\"%s\">%s...</a>) <br> %s", getBranchDescription(), getDiffUrl(), shortSha, getPusher());
	}

	public String getBranchDescription() {
		if (isPullRequest()) {
			return "Pull Request " + getPullRequestNumber();
		} else {
			return getBranch().replace("origin/", "");
		}
	}

	public String getPullRequestNumber() {
		return getPullRequest().getString("number");
	}

	public String getPusher() {
		if (isPullRequest()) {
			return payloadJson.getJSONObject("sender").getString("login");
		} else {
			return payloadJson.getJSONObject("pusher").getString("name");
		}
	}

	public String getDiffUrl() {
		if (isPullRequest()) {
			return getPullRequest().getString("html_url");
		} else {
			return payloadJson.getString("compare");
		}
	}

	public String getProjectUrl() {
		return getProject().getUrl();
	}

	public Iterable<GithubLogEntry> getLogEntries() {
		List<GithubLogEntry> logEntries = new ArrayList<GithubLogEntry>();
		if (!isPullRequest()) {
			JSONArray commits = payloadJson.getJSONArray("commits");
			for (Object commit : commits) {
				logEntries.add(convertToLogEntry((Map<String, Object>) commit));
			}
		}
		return logEntries;
	}

	private GithubLogEntry convertToLogEntry(Map<String, Object> commit) {
		return new GithubLogEntry(commit.get("message").toString(), commit.get("url").toString(), commit.get("id").toString());
	}
}
