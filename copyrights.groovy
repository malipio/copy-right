import groovy.io.FileType
import groovy.io.FileVisitResult

import java.time.YearMonth
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import groovy.cli.commons.CliBuilder

def cli = new CliBuilder(usage:'copyrights -y year -m month -r repoBaseDir', header:'Options:')
cli.help('print this message')
cli.y(args:1, argName:'year', required: true, 'submission year')
cli.m(args:1, argName:'month', required: true, 'submission month')
cli.r(args:1, argName:'repoBaseDir', required: true, 'baseDir with git repos (searched automatically')
def options = cli.parse(args)

if(options == null) {
    throw new IllegalArgumentException("wrong arguments")
}

def year = options.y as int
def month = options.m as int
def user = 'pima'
def repoBaseDir = options.r

def baseDir = "c:\\copyrights"
def ncscopyPath = "$baseDir\\ncscopy\\ncscopy.exe"


def gitRootRepos = findAllGitReposUnderDir(repoBaseDir)

println "Found repos: $gitRootRepos"

def startDate = YearMonth.of(year, month).atDay(1)
def endDate = YearMonth.of(year, month).atEndOfMonth()

def filesToZip = []

for (def repoDir : gitRootRepos) {
    def repoUrl = findRepoUrl(repoDir as String)
    def repoName = findRepoName(repoUrl)

    def fileName = new File(baseDir, "$year-${String.format("%02d", month)}_${repoName}.txt")
    println "Listing commits by $user during $startDate-$endDate in $repoDir to $fileName"

    def gitLogWithDiff = "git -C \"$repoDir\" log --since ${startDate} --until ${endDate} --author $user -p --all --full-history --reverse --raw --stat"
            .execute().text

    println "stats: ${gitLogWithDiff.readLines().size()} lines (${gitLogWithDiff.size()} bytes)"

    if (gitLogWithDiff.isBlank()) {
        println "No submissions for $repoUrl - skipping file"
    } else {
        fileName.withPrintWriter {
            it.println("Listing commits by $user during $startDate-$endDate for $repoUrl")
            it.println(gitLogWithDiff)
        }
        filesToZip += fileName
    }
}

def zipFile = new File(baseDir, "$year-${String.format("%02d", month)}.zip")
println "Zipping files: $filesToZip to $zipFile"

zipFiles(zipFile, filesToZip)

println "Zip file created"
filesToZip.each { it.delete() }
println "Temporary files deleted"

println "Sending to ncscopy"

sendToNcsCopy(ncscopyPath, year, month, zipFile)

private void sendToNcsCopy(ncscopyPath, year, month, File zipFile) {
    def submissionDate = YearMonth.of(year, month).atEndOfMonth().minusDays(4)
    process = "$ncscopyPath -date:$submissionDate ${year}_${String.format('%02d', month)} $zipFile".execute()
    process.waitForProcessOutput(System.out, System.err)

    if (process.exitValue() != 0) {
        throw new RuntimeException("non-zero exit status for process")
    }
}

private String findRepoName(String repoUrl) {
    repoUrl.split("/")[-1].replace(".git", "")
}

private String findRepoUrl(String repo) {
    "git -C \"$repo\" remote -v".execute().text.readLines().find {
        it.startsWith('origin')
    }.tokenize()[1]
}


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

private findAllGitReposUnderDir(repoBaseDir) {
    def gitRootRepos = []
    new File(repoBaseDir).traverse([type   : FileType.DIRECTORIES,
                                    preDir : {
                                        if (it.listFiles().find { dir -> dir.isDirectory() && dir.name == '.git' } != null) {
                                            gitRootRepos += it
                                            return FileVisitResult.SKIP_SUBTREE
                                        }
                                    },
                                    postDir: {}
    ])
    gitRootRepos
}
