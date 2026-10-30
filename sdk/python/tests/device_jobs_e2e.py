"""On-device managed job checks through the production control socket.

Requires local control running with job, process, file and workspace permissions. From a host, run
scripts/test-control-jobs-device.sh (debuggable install, run-as). Inside Termux, run with --local.
"""
import sys
import time

from termux_personal import TermuxClient
from termux_personal.domains import Files, Processes, Workspaces
from termux_personal.jobs import Jobs

PREFIX = "/data/data/com.termux/files/usr"
HOME = "/data/data/com.termux/files/home"


def drain(jobs, job_id, stream):
    data = b""
    for chunk in jobs.follow(job_id, stream, poll_interval=0.05):
        assert not chunk.gap, "unexpected gap in " + stream
        data += chunk.data
    return data


def check(name, condition, detail=""):
    if not condition:
        raise AssertionError(name + (": " + detail if detail else ""))
    print("ok", name)


# "--local" runs inside Termux against the socket directly, for devices where run-as is unavailable;
# otherwise the optional argument is an ADB serial.
LOCAL = len(sys.argv) > 1 and sys.argv[1] == "--local"
serial = None if LOCAL or len(sys.argv) < 2 else sys.argv[1]


def connect():
    if LOCAL:
        return TermuxClient.connect("/data/data/com.termux/files/control-ipc/control.sock")
    return TermuxClient.connect_adb(serial=serial)


with connect() as client:
    methods = set(client.capabilities()["methods"])
    missing = {"job.start", "job.get", "job.output.read", "process.list", "workspace.list"} - methods
    if missing:
        raise SystemExit("Grant job, process and workspace consent in the app first; missing " + ", ".join(sorted(missing)))
    jobs = Jobs(client)

    record = jobs.start(PREFIX + "/bin/sh", ["-c", "printf out; printf err >&2; exit 3"], cwd=HOME)
    result = jobs.wait(record.id, timeout=30)
    check("nonzero exit is reported", result.state == "exited" and result.exit_code == 3, repr(result))
    check("stdout is separate", drain(jobs, record.id, "stdout") == b"out")
    check("stderr is separate", drain(jobs, record.id, "stderr") == b"err")

    record = jobs.start(PREFIX + "/bin/true", [])
    result = jobs.wait(record.id, timeout=30)
    check("immediate exit", result.state == "exited" and result.exit_code == 0, repr(result))

    # 128 KiB on each stream at once: both pipes must be drained concurrently or the child blocks.
    script = "head -c 131072 /dev/zero; head -c 131072 /dev/zero >&2"
    record = jobs.start(PREFIX + "/bin/sh", ["-c", script])
    result = jobs.wait(record.id, timeout=60)
    check("large concurrent output exits", result.state == "exited" and result.exit_code == 0, repr(result))
    check("large stdout is complete", drain(jobs, record.id, "stdout") == b"\0" * 131072)
    check("large stderr is complete", drain(jobs, record.id, "stderr") == b"\0" * 131072)

    record = jobs.start(PREFIX + "/bin/cat", [], stdin="hello stdin")
    result = jobs.wait(record.id, timeout=30)
    check("stdin reaches the child", drain(jobs, record.id, "stdout") == b"hello stdin", repr(result))

    record = jobs.start(PREFIX + "/bin/sh", ["-c", "printf %s \"$JOB_VALUE\""], env={"JOB_VALUE": "overlay"})
    jobs.wait(record.id, timeout=30)
    check("environment overlay applies", drain(jobs, record.id, "stdout") == b"overlay")

    try:
        jobs.start("/system/bin/sh", ["-c", "true"])
        check("executable outside the prefix is refused", False)
    except Exception as exc:
        check("executable outside the prefix is refused", "denied" in str(exc).lower() or "invalid" in str(exc).lower(), str(exc))

    stop = client.describe().get("limits", {}).get("jobStopAvailable")
    check("running jobs can be stopped on this device", stop is True, repr(stop))

    # The shell and its background sleep share the job's process group; TERM must reach both.
    record = jobs.start(PREFIX + "/bin/sh", ["-c", "sleep 60 & sleep 60"])
    deadline = time.monotonic() + 10
    while jobs.get(record.id).state != "running" and time.monotonic() < deadline:
        time.sleep(0.05)
    started = time.monotonic()
    jobs.cancel(record.id)
    result = jobs.wait(record.id, timeout=20)
    check("cancel stops a running process tree", result.state == "cancelled" and time.monotonic() - started < 8, repr(result))
    check("cancelled output finishes", drain(jobs, record.id, "stdout") == b"")

    if "process.signal" in methods:
        record = jobs.start(PREFIX + "/bin/sleep", ["60"])
        deadline = time.monotonic() + 10
        while jobs.get(record.id).state != "running" and time.monotonic() < deadline:
            time.sleep(0.05)
        Processes(client).signal_job(record.id, "INT")
        result = jobs.wait(record.id, timeout=20)
        check("process.signal reaches an owned job", result.state == "exited" and result.exit_code == 130, repr(result))
    else:
        print("skip signal check: process.signal not granted")

    record = jobs.start(PREFIX + "/bin/sleep", ["60"], timeout_ms=500)
    result = jobs.wait(record.id, timeout=20)
    check("wall timeout stops a running job", result.state == "timed_out", repr(result))

    # A child that ignores TERM is killed after the grace period.
    record = jobs.start(PREFIX + "/bin/sh", ["-c", "trap '' TERM; while :; do sleep 1; done"], timeout_ms=200)
    result = jobs.wait(record.id, timeout=30)
    check("KILL follows an ignored TERM", result.state == "timed_out", repr(result))

    listed, _ = jobs.list()
    check("job.list returns started jobs", any(item.id == record.id for item in listed))

    processes = Processes(client).list(scope="all-visible")
    check("process.list returns rows", isinstance(processes.get("processes"), list) and len(processes["processes"]) > 0)

    if "file.read" in methods:
        files = Files(client)
        check("file roots are home and prefix", {root["id"] for root in files.roots()} == {"home", "prefix"})
        record = jobs.start(PREFIX + "/bin/sh", ["-c", "mkdir -p e2e && printf hello > e2e/a.txt && ln -sfn " + PREFIX + "/etc e2e/link"])
        check("fixture job exits", jobs.wait(record.id, timeout=30).exit_code == 0)
        entries, _ = files.list("home", path="e2e")
        types = {entry.name: entry.type for entry in entries}
        check("listing classifies a link without following it", types == {"a.txt": "file", "link": "symlink"}, repr(types))
        check("file.read returns bytes", files.read("home", "e2e/a.txt").data == b"hello")
        for denied in ("e2e/link/profile", "e2e/link"):
            try:
                files.read("home", denied)
                check("read through a link is refused: " + denied, False)
            except Exception as exc:
                check("read through a link is refused: " + denied, "DENIED" in str(exc) or "UNSUPPORTED" in str(exc), str(exc))
        if "file.transfer.begin" in methods:
            payload = bytes(range(256)) * 1024  # 256 KiB: four chunks
            entry, digest = files.upload("home", "e2e/upload.bin", payload)
            check("upload commits the exact bytes", entry.size == len(payload) and files.read("home", "e2e/upload.bin").data == payload[:65536])
            try:
                files.upload("home", "e2e/upload.bin", b"again")
                check("upload never overwrites without a revision", False)
            except Exception as exc:
                check("upload never overwrites without a revision", "CONFLICT" in str(exc), str(exc))
        if "file.rename" in methods:
            with connect() as fresh:
                writer = Files(fresh)
                writer.mkdir("home", "e2e/dir")
                renamed = writer.rename("home", "e2e/a.txt", "e2e/dir/a.txt")
                check("rename moves inside the root", renamed.path == "e2e/dir/a.txt")
                try:
                    writer.mkdir("home", "e2e/link/escape")
                    check("mkdir through a link is refused", False)
                except Exception as exc:
                    check("mkdir through a link is refused", "DENIED" in str(exc), str(exc))
                writer.delete("home", "e2e/dir/a.txt")
                writer.delete("home", "e2e/dir")
        jobs.wait(jobs.start(PREFIX + "/bin/rm", ["-rf", HOME + "/e2e"]).id, timeout=30)
    else:
        print("skip file checks: file.read not granted")

    workspaces = Workspaces(client)
    created = workspaces.create("device-e2e-" + str(int(time.time())), "home", "")
    try:
        check("workspace is listed", any(item.id == created.id for item in workspaces.list()[0]))
        record = workspaces.git(created.id, "status")
        result = jobs.wait(record.id, timeout=30)
        check("workspace.git runs as a managed job", result.state in ("exited", "failed"), repr(result))
    finally:
        workspaces.remove(created.id)

    if "workspace.git.change" in methods:
        setup = "rm -rf e2e-repo && mkdir e2e-repo && cd e2e-repo && git init -q && git config user.email e2e@local && " \
                "git config user.name e2e && printf x > a.txt"
        check("repository fixture", jobs.wait(jobs.start(PREFIX + "/bin/sh", ["-c", setup]).id, timeout=30).exit_code == 0)
        repo = workspaces.create("device-git-" + str(int(time.time())), "home", "e2e-repo")
        try:
            staged = jobs.wait(workspaces.git_change(repo.id, "stage", paths=["a.txt"]).id, timeout=30)
            check("git stage runs in the workspace", staged.exit_code == 0, repr(staged))
            committed = jobs.wait(workspaces.git_change(repo.id, "commit", message="--amend is just a message").id, timeout=30)
            check("git commit takes the message as a value", committed.exit_code == 0, repr(committed))
            log = jobs.wait(workspaces.git(repo.id, "log").id, timeout=30)
            check("commit is in the log", b"--amend is just a message" in drain(jobs, log.id, "stdout"))
        finally:
            workspaces.remove(repo.id)
            jobs.wait(jobs.start(PREFIX + "/bin/rm", ["-rf", HOME + "/e2e-repo"]).id, timeout=30)

print("Android managed jobs, processes and workspaces passed")
