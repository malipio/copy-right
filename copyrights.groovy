@Grapes([
        @Grab(group = 'org.apache.httpcomponents.client5', module = 'httpclient5', version = '5.0.3'),
        @Grab(group = 'org.slf4j', module = 'slf4j-simple', version = '1.7.30')
])
import groovy.io.FileType
import groovy.io.FileVisitResult
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.xml.XmlSlurper
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.CredentialsStore
import org.apache.hc.client5.http.auth.NTCredentials
import org.apache.hc.client5.http.classic.HttpClient
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.io.entity.FileEntity
import org.apache.hc.core5.http.io.entity.StringEntity
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder
import org.apache.hc.core5.http.message.BasicNameValuePair
import org.apache.hc.core5.net.URIBuilder

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoUnit
import java.util.stream.Collectors
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import groovy.cli.commons.CliBuilder


enum TimeframeType {
    LAST_SUBMISSION

}

class Timeframe {
    LocalDateTime start, end

    @Override
    String toString() {
        return "(${start} to ${end})"
    }
}

def cli = new CliBuilder(usage: 'groovy copyrights.groovy --toolkit-login <login> --toolkit-password-base64 <password>', header: 'Options:')
cli.h(longOpt: 'help', 'print this message')
cli.r(longOpt: 'repo', args: 1, argName: 'repoBaseDir', required: true, 'baseDir with git repos (searched automatically)')
cli.g(longOpt: 'git-user', args: 1, argName: 'gitUser', required: false, defaultValue: System.getenv('USERNAME'),
        'Git user name (defaults to %USERNAME%)')
cli.z(longOpt: 'zip-dir', args: 1, argName: 'zipDir', required: false, defaultValue: new File("").getAbsolutePath(),
        'Folder to store ZIP files, defaults to working dir') // TODO rename to working-dir
cli.l(longOpt: 'toolkit-login', args: 1, required: false, defaultValue: System.getenv('USERNAME'),
        'Toolkit login (defaults to %USERNAME%)')
cli.p(longOpt: 'toolkit-password-base64', args: 1, required: true,
        'Toolkit password Base64 encoded')
//cli.d(longOpt: 'dry-run', 'Do not send to NCSCOPY') // TODO
//cli.c(longOpt: 'config-file', 'Reads config from YAML file in addition to command line') // TODO or read from zipDir?
def currentDateOption = cli.option([longOpt: 'date', args: 1], LocalDateTime,
        'Default value: now. Date (and possibly time) all timeframe calculation are relative to.')
def timeFrameOption = cli.option([longOpt: 'timeframe', args: 1], TimeframeType,
        "Time frame for report generation. Choose from: ${TimeframeType.values().join(', ')}.")
def options = cli.parse(args)

if (options == null) {
    throw new IllegalArgumentException("wrong arguments")
}

final DateTimeFormatter fileDateTimeFormatter = new DateTimeFormatterBuilder()
        .appendPattern('yyyyMMdd_HHmm').toFormatter()
final LocalDateTime currentDate = options.getOptionValue(currentDateOption, LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))
final TimeframeType timeframeType = options.getOptionValue(timeFrameOption, TimeframeType.LAST_SUBMISSION)
def gitUser = options.'git-user' as String
def repoBaseDir = options.repo as String
def zipDir = options.'zip-dir' as String
def login = options.'toolkit-login' as String
def password = new String(Base64.decoder.decode(options.'toolkit-password-base64' as String), 'UTF-8')

println "Finding all git repos under $repoBaseDir"
List<String> gitRootRepos = findAllGitReposUnderDir(repoBaseDir)

println "Found ${gitRootRepos.size()} repos: $gitRootRepos"

if (gitRootRepos.empty) {
    println "No git repositories found. Exiting."
    return
}

def ncsCopyRestClient = new NcsCopyRestClient('NCDMZ', login, password)
def itemTitle = formatItemTitle(currentDate)

Timeframe timeframe = createTimeframe(timeframeType, currentDate, ncsCopyRestClient, itemTitle)

private Timeframe createTimeframe(TimeframeType timeframeType, LocalDateTime currentDate,
                                  NcsCopyRestClient ncsCopyRestClient, String itemTitle) {
    switch (timeframeType) {
        case TimeframeType.LAST_SUBMISSION:
            LocalDateTime lastSubmissionDate = ncsCopyRestClient.findExistingItemSubmissionDate(itemTitle) ?:
                    currentDate.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            return new Timeframe(start: lastSubmissionDate, end: currentDate)
            break
        default:
            throw new UnsupportedOperationException("${timeframeType} is unsupported!")
    }
}

long start = System.currentTimeMillis()
List filesToZip = gitRootRepos.parallelStream().map() { repoDir ->
    println "Entering $repoDir"
    def repoUrl = findRepoRemoteUrl(repoDir as String)
    if (repoUrl == null) {
        println "WARNING: $repoDir is not published to remote url. SKIPPING"
        return []
    }
    def repoName = findRepoName(repoUrl)

    def fileName = new File(zipDir, formatRepoDiffFileName(fileDateTimeFormatter, timeframe, currentDate, repoName))
    println "Listing commits by $gitUser during $timeframe in $repoDir to $fileName"

    def gitLogWithDiff = ['git', '-C', repoDir, 'log',
                          '--date=local', '--since', timeframe.start, '--until', timeframe.end,
                          '--author', gitUser,
                          '-p', '--all', '--full-history', '--reverse', '--raw', '--stat']
            .execute().text

    println "stats: ${gitLogWithDiff.readLines().size()} lines (${gitLogWithDiff.size()} bytes)"

    if (gitLogWithDiff.isBlank()) {
        println "No submissions for $repoUrl - skipping file"
        return []
    } else {
        fileName.withPrintWriter {
            it.println("Listing commits by $gitUser during $timeframe for $repoUrl")
            it.println(gitLogWithDiff)
        }
        println "Done with $repoDir."
        return [fileName]
    }
}.collect(Collectors.toList()).flatten()

println "Scanning and listing submissions took ${(System.currentTimeMillis() - start) / 1000}s"

if (filesToZip.empty) {
    println "No submissions found for given time frame. Exiting"
    return
}

println "Changesets total size: ${filesToZip.sum { it.size() } >> 10}KB"

def zipFile = new File(zipDir, formatZipName(fileDateTimeFormatter, timeframe, currentDate))

println "Zipping files: $filesToZip to $zipFile"

zipFiles(zipFile, filesToZip)

println "Zip file created. Zip size: ${zipFile.size() >> 10}KB"
filesToZip.each { it.delete() }
println "Temporary files deleted"

println "Sending to ncscopy"

def submissionDate = currentDate

ncsCopyRestClient.submitCopyrights(itemTitle, submissionDate, zipFile)

private String formatItemTitle(LocalDateTime currentDate) {
    return "${currentDate.year}_${String.format('%02d', currentDate.monthValue)}"
}

private String formatZipName(DateTimeFormatter formatter, Timeframe timeframe, LocalDateTime currentDate) {

    return "${timeframe.start.format(formatter)}-${timeframe.end.format(formatter)}_at_${currentDate.format(formatter)}.zip"
}

private String formatRepoDiffFileName(DateTimeFormatter formatter, Timeframe timeframe, LocalDateTime currentDate, String repoName) {
    return "${timeframe.start.format(formatter)}-${timeframe.end.format(formatter)}_${repoName}.txt"
}

private String findRepoName(String repoUrl) {
    repoUrl.split("/")[-1].replace(".git", "")
}

private String findRepoRemoteUrl(String repo) {
    def originLine = ['git', '-C', repo, 'remote', '-v'].execute().text.readLines().find {
        it.startsWith('origin')
    }

    if (!originLine) {
        return null
    } else {
        return originLine.tokenize()[1]
    }
}


@CompileStatic
def zipFiles(File zipFileName, List<File> files) {
    ZipOutputStream zipFile = new ZipOutputStream(new FileOutputStream(zipFileName))
    files.each { file ->
        if (file.isFile()) {
            zipFile.putNextEntry(new ZipEntry(file.name))
            def buffer = new byte[file.size()]
            file.withInputStream {
                zipFile.write(buffer, 0, it.read(buffer))
            }
            zipFile.closeEntry()
        }
    }
    zipFile.close()
}

@CompileStatic
private List<String> findAllGitReposUnderDir(String repoBaseDir) {
    List<String> gitRootRepos = []
    new File(repoBaseDir).traverse([type   : FileType.DIRECTORIES,
                                    preDir : {
                                        def gitDir = new File(it as File, '.git')
                                        if (gitDir.exists() && gitDir.isDirectory()) {
                                            gitRootRepos += it as String
                                            print "."
                                            return FileVisitResult.SKIP_SUBTREE
                                        }
                                    },
                                    postDir: {}
    ])
    println ''
    gitRootRepos
}

class NcsCopyRestClient {
    private def hostUrl = 'https://goto.netcompany.com'
    private def webApiUrl = "$hostUrl/cases/GTE106/NCSCOPY/_api/web"
    private def contextInfoUrl = "$hostUrl/cases/GTE106/NCSCOPY/_api/contextinfo"

    private HttpClient client
    private def folderName

    NcsCopyRestClient(String domain, String login, String password) {
        CredentialsStore credsProvider = new BasicCredentialsProvider()
        credsProvider.setCredentials(new AuthScope(HttpHost.create(hostUrl)),
                new NTCredentials(login, password.toCharArray(), null, domain))
        this.client = HttpClientBuilder.create().setDefaultCredentialsProvider(credsProvider).build()
        this.folderName = login.toUpperCase()
    }

    String submitCopyrights(String itemTitle, LocalDateTime submissionDate, File attachmentFile) {
        def existingItemId = findExistingItem(itemTitle)?.ID
        if (existingItemId) {
            println "Work item with same title exists, ID=$existingItemId"
        }

        def digestResponse = client.execute(ClassicRequestBuilder.post(contextInfoUrl)
                .setEntity('', ContentType.DEFAULT_TEXT).build()).entity.content.text
        println digestResponse
        String formDigest = new XmlSlurper(false, true).parseText(digestResponse).FormDigestValue.text()
        println 'Digest is ' + formDigest

        def itemId = existingItemId ?: http(postNewItem(formDigest, folderName, itemTitle)).value.find { it.FieldName == 'Id' }?.FieldValue

        def attachmentUri = uploadAttachment(itemId, attachmentFile, formDigest)

        updateAuthorAndSubmissionDate(itemId, formDigest, submissionDate)

        def itemInfo = existingItemId ? "Updated existing item with ID=${existingItemId}" : "New work item created with ID=${itemId}"
        println "${itemInfo} and with attachment saved in ${hostUrl}${attachmentUri}"
        println "Direct Link: $hostUrl/cases/GTE106/NCSCOPY/Lists/WorkItems/DispForm.aspx?ID=$itemId"
        return itemId
    }

    private String uploadAttachment(itemId, File attachmentFile, String formDigest) {
        http(ClassicRequestBuilder.post()
                .setUri("${webApiUrl}/lists/GetByTitle('WorkItems')/items($itemId)/AttachmentFiles/add(FileName='${attachmentFile.name}')")
                .setHeader('X-RequestDigest', formDigest)
                .setHeader('Accept', ContentType.APPLICATION_JSON.toString())
                .setEntity(new FileEntity(attachmentFile, ContentType.APPLICATION_OCTET_STREAM))
                .build()).ServerRelativeUrl
    }

    private void updateAuthorAndSubmissionDate(itemId, String formDigest, LocalDateTime submissionDate) {
        def authorId = httpGet("${webApiUrl}/lists/GetByTitle('WorkItems')/items($itemId)").AuthorId
        http(ClassicRequestBuilder.post()
                .setUri("${webApiUrl}/lists/GetByTitle('WorkItems')/items(${itemId})")
                .setHeader('Accept', "application/json;odata=verbose")
                .setHeader('If-Match', '*')
                .setHeader('X-HTTP-Method', "MERGE")
                .setHeader('X-RequestDigest', formDigest)
                .setEntity(new StringEntity("""{
  "ClassificationId": 12,
  "EmployeeId": $authorId,
  "SubmissionDate": "$submissionDate"
}
""", ContentType.APPLICATION_JSON)).build())
    }

    private def findExistingItem(String itemTitle) {
        httpGet(new URIBuilder("${webApiUrl}/lists/GetByTitle('WorkItems')/items")
                .setParameters(new BasicNameValuePair('$filter', "Title eq '${itemTitle}'"))
                .toString())
                .value ?[0]

    }

    LocalDateTime findExistingItemSubmissionDate(String itemTitle) {
        def submissionDateString = findExistingItem(itemTitle)?.SubmissionDate as String

        return submissionDateString ? LocalDateTime.ofInstant(OffsetDateTime.parse(submissionDateString).toInstant(), ZoneId.systemDefault()) : null
    }

    private Object httpGet(String element) {
        return http(ClassicRequestBuilder.get()
                .setUri(element)
                .setHeader("Accept", ContentType.APPLICATION_JSON.toString())
                .build())
    }

    private Object http(ClassicHttpRequest request) {
        println "${request.getMethod()} on ${request.getRequestUri()}"
        println "request body: ${request.entity == null ? '<EMPTY>' : request.entity instanceof StringEntity ? request.entity.content.text : '<STREAM>'}"
        def response = client.execute(request) as ClassicHttpResponse
        println "RESPONSE: ${response}"
        def body = response.entity?.content?.text
        println "response body: ${body == null ? '<EMPTY>' : body}"
        return new JsonSlurper().parseText(body ?: '{}')
    }

    private HttpPost postNewItem(String formDigest, String folderName, String itemTitle) {
        def post = new HttpPost("$webApiUrl/lists/GetByTitle('WorkItems')/AddValidateUpdateItemUsingPath")
        post.setHeader("Accept", "application/json;odata=nometadata")
        post.setHeader('X-RequestDigest', formDigest)
        post.setEntity(new StringEntity("""{
    "listItemCreateInfo": {
    "FolderPath": {
        "DecodedUrl": "https://goto.netcompany.com/cases/GTE106/NCSCOPY/Lists/WorkItems/${folderName}"
    },
    "UnderlyingObjectType": 0
},
    "formValues": [
        {
            "FieldName": "Title",
            "FieldValue": "${itemTitle}"
        }
],
    "bNewDocumentUpdate": false
}
""", ContentType.APPLICATION_JSON))
        return post
    }
}
