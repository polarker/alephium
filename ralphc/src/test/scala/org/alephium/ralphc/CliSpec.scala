// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.ralphc

import java.nio.file.{Files, Paths}

import org.alephium.util.AlephiumSpec

class CliSpec extends AlephiumSpec {
  val baseDir     = "src/test/resources"
  val project1    = baseDir + "/project1"
  val project2    = baseDir + "/project2"
  val project3    = baseDir + "/project3"
  val project31   = project3 + "/project1"
  val project32   = project3 + "/project2"
  val contracts1  = project1 + "/contracts"
  val contracts31 = project31 + "/contracts"

  def assertProject(
      sourcePath: String,
      artifactsPath: String,
      isRecode: Boolean = false
  ) = {
    val cli = Cli()
    assert(cli.call(Array("-c", sourcePath, "-a", artifactsPath)) == 0)
    cli.configs
      .configs()
      .foreach(config => {
        val latestArchives =
          Compiler.getSourceFiles(config.artifactsPath().toFile, ".json")
        var archivesPath = config.contractsPath().getParent.resolve("artifacts")
        if (isRecode) {
          archivesPath = config.contractsPath().resolve("artifacts")
        }
        val archivesFile = archivesPath.toFile
        val archives = Compiler
          .getSourceFiles(archivesFile, ".json")
          .map(file => {
            val path = file.toPath
            path.subpath(archivesFile.toPath.getNameCount, path.getNameCount)
          })
          .sorted
        latestArchives
          .map(file => {
            val path = file.toPath
            path.subpath(config.artifactsPath().getNameCount, path.getNameCount)
          })
          .sorted is archives
      })
  }

  it should "be able to compile project" in {
    val baseArtifacts = Files.createTempDirectory("projects").toFile.getPath
    val artifacts1    = baseArtifacts + "/artifacts1"
    val artifacts2    = baseArtifacts + "/artifacts2"
    val artifacts31   = baseArtifacts + "/artifacts31"
    val artifacts32   = baseArtifacts + "/artifacts32"
    Paths.get(artifacts1).toFile.mkdirs()
    Paths.get(artifacts2).toFile.mkdirs()
    Paths.get(artifacts31).toFile.mkdirs()
    Paths.get(artifacts32).toFile.mkdirs()
    assertProject(contracts1, artifacts1)
    assertProject(contracts31, artifacts31)
    assertProject(project2, artifacts2, isRecode = true)
    assertProject(project32, artifacts32, isRecode = true)
    assert(Compiler.deleteFile(Paths.get(baseArtifacts).toFile))
  }

  it should "be able to compile multi-project contracts" in {
    val baseArtifacts = Files.createTempDirectory("projects").toFile.getPath
    val artifacts1    = baseArtifacts + "/artifacts1"
    val artifacts2    = baseArtifacts + "/artifacts2"
    val artifacts31   = baseArtifacts + "/artifacts31"
    val artifacts32   = baseArtifacts + "/artifacts32"
    Paths.get(artifacts1).toFile.mkdirs()
    Paths.get(artifacts2).toFile.mkdirs()
    Paths.get(artifacts31).toFile.mkdirs()
    Paths.get(artifacts32).toFile.mkdirs()
    assertProject(contracts1 + "," + contracts31, artifacts1 + "," + artifacts31)
    assertProject(project2 + "," + project32, artifacts2 + "," + artifacts32, isRecode = true)
    assert(Compiler.deleteFile(Paths.get(baseArtifacts).toFile))
  }

  it should "not to compile contracts" in {
    assert(Cli().call(Array("-c", contracts1 + "," + contracts31, "-a", "")) != 0)
  }
}