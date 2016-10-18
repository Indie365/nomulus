#!/usr/bin/python
# Copyright 2016 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


"""Script for generating eclipse .project and .classpath files."""

import os
import subprocess
import sys


def bazel_info(key):
    """Invokes the bazel info subcommand.

    Invokes bazel info on the command line, parses the output and returns it

    Args:
        key: The argument that is passed to bazel info. See
           http://bazel.io/docs/bazel-user-manual.html#info for example values.

    Returns:
        The output of the bazel info invocation as a string. If multiple lines
        are returned by bazel info, only the first line is returned.
    """
    bazel_process = subprocess.Popen(["bazel", "info", key],
                                     stdout=subprocess.PIPE)
    result = [line.strip() for line in iter(bazel_process.stdout.readline, "")]
    return result[0]


def classpath_entry_xml(kind, path):
    """Generates an eclipse xml classpath entry.

    Args:
        kind: Kind of classpath entry.
            Example values are 'lib', 'src', and 'con'
        path: Absolute or relative path to the referenced resource.
            Paths that are not absolute are relative to the project root.

    Returns:
        xml classpath entry element with the specified kind and path.
    """
    return "<classpathentry kind=\"{kind}\" path=\"{path}\"/>".format(
        kind=kind, path=path)


def classpath_xml(entries):
    """Produces the xml for an eclipse classpath file.

    Args:
        entries: list of dictionaries in the form of:
            {
                "kind": (str),
                "path": (str)
            }

    Returns:
        Contents of the eclipse .classpath file.
    """
    entries_xml = "\n".join(
        ["  " + classpath_entry_xml(**entry) for entry in entries])
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            "<classpath>\n"
            "{entries}"
            "\n</classpath>").format(entries=entries_xml)


def build_classpath():
    """Builds eclipse classpath file.

    Generates an eclipse .classpath file that has references to all of the
    project source folders, autogenerated source code, and external binary
    dependencies.

    Returns:
        Contents of the eclipse .classpath file.
    """
    # source folder for autogenerated files must reference
    # symlinked bazel-genfiles folder inside of the project.
    bazel_genfiles = bazel_info("bazel-genfiles")
    classpath_entries = [
        {"kind": "con", "path": "org.eclipse.jdt.launching.JRE_CONTAINER"},
        {"kind": "src", "path": "java"},
        {"kind": "src", "path": "javatests"},
        {"kind": "src", "path": "bazel-genfiles/java"},
        {
            "kind": "lib",
            "path": ("%s/java/google/"
                     "registry/eclipse/eclipse_deps.jar" % bazel_genfiles)
        },
        {"kind": "output", "path": "bin"},
    ]
    return classpath_xml(classpath_entries)


def build_project(project_name):
    """Builds eclipse project file.

    Uses a very simple template to generate an eclipse .project file
    with a configurable project name.

    Args:
        project_name: Name of the eclipse project. When importing the project
            into an eclipse workspace, this is the name that will be shown.
    Returns:
        Contents of the eclipse .project file.
    """
    template = """<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
    <name>{project_name}</name>
    <comment>
    </comment>
    <projects>
    </projects>
    <buildSpec>
        <buildCommand>
            <name>org.python.pydev.PyDevBuilder</name>
            <arguments>
            </arguments>
        </buildCommand>
        <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments>
            </arguments>
        </buildCommand>
    </buildSpec>
    <natures>
        <nature>org.eclipse.jdt.core.javanature</nature>
        <nature>org.python.pydev.pythonNature</nature>
    </natures>
</projectDescription>"""
    return template.format(project_name=project_name)


def factorypath_entry_xml(kind, entry_id):
    """Generates an eclipse xml factorypath entry.

    Args:
        kind: Kind of factorypath entry.
            Example values are 'PLUGIN', 'WKSPJAR'
        entry_id: Unique identifier for the factorypath entry

    Returns:
        xml factorypath entry element with the specified kind and id.
    """
    return ("<factorypathentry kind=\"{kind}\" id=\"{entry_id}\" "
            "enabled=\"true\" runInBatchMode=\"false\"/>").format(
                kind=kind, entry_id=entry_id)


def factorypath_xml(entries):
    """Produces the xml for an eclipse factorypath file.

    Args:
        entries: list of dictionaries in the form of:
            {
                "kind": (str),
                "entry_id": (str)
            }

    Returns:
        Contents of the eclipse .factorypath file.
    """
    entries_xml = "\n".join(
        ["  " + factorypath_entry_xml(**entry) for entry in entries])
    return ("<factorypath>\n"
            "{entries}"
            "\n</factorypath>").format(entries=entries_xml)


def build_factorypath():
    """Builds eclipse factorypath file.

    Generates an eclipse .factorypath file that links to the jar containing
    all required annotation processors for the project.

    Returns:
        Contents of the eclipse .factorypath file.
    """
    bazel_bin = bazel_info("bazel-bin")
    annotations_jar = os.path.join(
        bazel_bin,
        "java/google/registry/eclipse"
        "/annotation_processors_ide_deploy.jar")
    factorypath_entries = [
        {
            "kind": "PLUGIN",
            "entry_id": "org.eclipse.jst.ws.annotations.core",
        },
        {
            "kind": "EXTJAR",
            "entry_id": annotations_jar,
        }
    ]
    return factorypath_xml(factorypath_entries)


def build_dependencies():
    """Builds dependencies for producing eclipse project files.

    Runs bazel build for the entire project and builds a single jar with all
    binary dependencies for eclipse to compile the project.

    Raises:
        subprocess.CalledProcessError: A bazel build failed
    """
    # Build entire project first
    subprocess.check_call([
        "bazel",
        "build",
        "//java/google/registry/...",
        "//javatests/google/registry/...",
    ])

    # Builds a giant jar of all compile-time dependencies of the project
    subprocess.check_call([
        "bazel",
        "build",
        "//java/google/registry/eclipse:eclipse_deps",
    ])

    # Builds a jar with all annotation processors
    subprocess.check_call([
        "bazel",
        "build",
        "//java/google/registry/eclipse"
        ":annotation_processors_ide_deploy.jar",
    ])


def main():
    """Builds eclipse project files.

    Before building the eclipse files, a working bazel build is required.
    After building the eclipse dependencies jar and the tests, eclipse
    project files are produced.
    """
    build_dependencies()
    workspace_directory = bazel_info("workspace")
    classpath = build_classpath()
    with open(os.path.join(workspace_directory, ".classpath"),
              "w") as classpath_file:
        classpath_file.write(classpath)
    if len(sys.argv) > 1:
        project_name = sys.argv[1]
    else:
        project_name = "domain-registry"
    project = build_project(project_name)
    with open(os.path.join(workspace_directory, ".project"),
              "w") as project_file:
        project_file.write(project)
    factorypath = build_factorypath()
    with open(os.path.join(workspace_directory, ".factorypath"),
              "w") as factorypath_file:
        factorypath_file.write(factorypath)
    if not os.path.exists(".settings"):
        os.makedirs(".settings")
    # XXX: Avoid wiping out existing settings from org.eclipse.jdt.core.prefs
    with open(os.path.join(workspace_directory,
                           ".settings",
                           "org.eclipse.jdt.core.prefs"), "w") as prefs_file:
        prefs_file.write("\n".join([
            "eclipse.preferences.version=1",
            "org.eclipse.jdt.core.compiler.processAnnotations=enabled",
        ]))
    with open(os.path.join(workspace_directory,
                           ".settings",
                           "org.eclipse.jdt.apt.core.prefs"),
              "w") as prefs_file:
        prefs_file.write("\n".join([
            "eclipse.preferences.version=1",
            "org.eclipse.jdt.apt.aptEnabled=true",
            "org.eclipse.jdt.apt.genSrcDir=autogenerated",
            "org.eclipse.jdt.apt.reconcileEnabled=true",
        ]))


if __name__ == "__main__":
    main()
