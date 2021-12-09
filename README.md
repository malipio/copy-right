# copy-right
# Description
This commandline tool allows you to generate and submit a diff of your work for copyrights tracking purposes.
Main goal is to automate the whole process, ie. by scheduling a daily job that will do that for a user.

# CLI help
```
usage: groovy copyrights.groovy --toolkit-login <login>
              --toolkit-password-base64 <password>
Options:
    --date <arg>                      Default value: now. Date (and
                                      possibly time) all timeframe
                                      calculation are relative to.
 -g,--git-user <gitUser>              Git user name (defaults to
                                      %USERNAME%)
 -h,--help                            print this message
 -l,--toolkit-login <arg>             Toolkit login (defaults to
                                      %USERNAME%)
 -p,--toolkit-password-base64 <arg>   Toolkit password Base64 encoded
 -r,--repo <repoBaseDir>              baseDir with git repos (searched
                                      automatically)
    --timeframe <arg>                 Time frame for report generation.
                                      Choose from: LAST_SUBMISSION.
 -z,--zip-dir <zipDir>                Folder to store ZIP files, defaults
                                      to working dir
```
# Running
Assuming that user `puma` with password `admin1` (base64 encoded to `YWRtaW4x`) would like to submit all his copyrights since
last submission. 
User has all his git repositories under `c:\projects` and would like to save a copy of submitted data into 
`c:\copyrights` folder.

## Running as docker image
### Prerequisites
* docker installed
### CLI
```
docker run -it -v c:\projects:/projects -v c:\copyrights:/copyrights ghcr.io/malipio/copy-right/copyrights:latest \
    --repo /projects \
    --toolkit-login puma \
    --git-user puma \ 
    --toolkit-password-base64 YWRtaW4x \
    --zip-dir /copyrights 
```
Above command does not work when trying to run from Git Bash, use PowerShell when running on Windows.
## Running as groovy script
### Prerequisites
* [groovy 3.x](https://groovy.apache.org/download.html) installed and available on path
* git installed and available on path
### CLI
```
groovy https://raw.githubusercontent.com/malipio/copy-right/main/src/main/groovy/copyrights.groovy \
    --repo 'c:\projects' 
    --toolkit-password-base64 'YWRtaW4x' 
    --zip-dir 'c:\copyrights'
```

# Scheduling on Windows
You can install a schedule by typing:
```
schtasks /Create /XML copyrights_task.xml /TN My\example_copyright
```
Please remember to edit [copyrights_task.xml](copyrights_task.xml) file and 
configure `Command`, `Arguments` and `WorkingDirectory`!

# Scheduling on Linux
Left as an exercise to the reader.

# Ideas for future improvements (DRAFT)
* customize timeframe 
  * `--timeframe=[this_month/last24h/this_week/]last_submission`
* customize current date 
  * `[--date=2021-10-10(T12:00:00)/LocalDate(time)]`
* customize file naming convention 
  * `zip filename= {from}-{to}_at_{timestamp}.zip` `txt filename= {from}-{to}_{repo}.txt`
* dry run mode
  * `--dry-run`
* configurable url of toolkit
  * `--toolkit-url`