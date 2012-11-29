package org.randi3.web.snippet

import java.util.Date

import java.util.Locale
import net.liftweb.http.StatefulSnippet
import org.randi3.model.criterion._
import org.randi3.model.criterion.constraint._
import scala.xml._
import scala.xml.Group
import scala.xml.NodeSeq
import scala.xml.Text

import org.randi3.web.lib.DependencyFactory

import net.liftweb.common._
import net.liftweb.http.S._

import net.liftweb.http._
import js.JE

import js.JsCmds.Replace

import js.JsCmds.SetHtml
import net.liftweb.util.Helpers._
import net.liftweb._

import common.Full
import http.SHtml._
import org.randi3.randomization._
import org.randi3.randomization.configuration._
import org.apache.commons.math3.random.MersenneTwister


import org.randi3.model._

import scalaz.{Empty => _, Node => _, _}
import Scalaz._
import org.randi3.web.util.{CurrentUser, Utility, CurrentTrial}
import org.joda.time.LocalDate
import collection.mutable.{HashMap, ListBuffer, HashSet}
import org.joda.time.format.DateTimeFormat

import scala.Some
import xml.Text
import scala.Left

import scala.Right
import collection.mutable

class TrialSnippet extends StatefulSnippet with HelperSnippet {

  private val trialSiteService = DependencyFactory.trialSiteService
  private val userService = DependencyFactory.userService
  private val trialService = DependencyFactory.trialService
  private val randomizationPluginManager = DependencyFactory.randomizationPluginManager

  private var selectedTrialSubject: Option[TrialSubject] = None

  def dispatch = {
    case "info" => redirectTo("/trial/list")
    case "trials" => trials _
    case "create" => create _
    case "show" => redirectTo("/trial/generalInformation")
    case "editView" => redirectTo("/trial/editUsers")
    case "edit" => edit _
    case "editStatus" => editStatus _
    case "editUsers" => editUsers _
    case "randomize" => randomize _
    case "trialSubjects" => trialSubjects _
    case "showTrialSubject" => showTrialSubject _
    case "confirmDelete" => confirmDelete _
  }

  //TODO enumeration & text
  private val criterionTypes = Seq(("DateCriterion", "DateCriterion"),
    ("IntegerCriterion", "IntegerCriterion"),
    ("DoubleCriterion", "DoubleCriterion"),
    ("FreeTextCriterion", "FreeTextCriterion"),
    ("OrdinalCriterion", "OrdinalCriterion"))

  private var name = ""
  private var stageCount = 1
  private var abbreviation = ""
  private var description = ""
  private var startDate = new LocalDate
  private var endDate = new LocalDate
  private var trialSites = trialSiteService.getAll.toOption.get.map(trialSite => (trialSite, trialSite.name))
  //TODO error handling
  private var actualTrialSite: TrialSite = if (trialSites.isEmpty) null else trialSites.head._1
  private var principleInvestigator: org.randi3.model.User = null

  private var trialStatusTmp = TrialStatus.IN_PREPARATION.toString
  private var nameNewTreatmentArm = ""
  private var descriptionNewTreatmentArm = ""
  private var plannedSubjectSizeNewTreatmentArm = 0

  private var participatingSiteTmp: TrialSite = if (trialSites.isEmpty) null else trialSites.head._1
  private val participatingSites = new HashSet[TrialSite]()

  private val armsTmp = getEmptyArmsTmpList


  private def getEmptyArmsTmpList: ListBuffer[TreatmentArmTmp] = {
    val result = new ListBuffer[TreatmentArmTmp]()
    result += new TreatmentArmTmp(Int.MinValue, 0, "", "", 0)
    result
  }

  private var identificationCreationTypeTmp = TrialSubjectIdentificationCreationType.CONTINUOUS_COUNTER.toString

  private var criterionTypeTmp = "DateCriterion"

  private val criterionsTmp = new ListBuffer[CriterionTmp]()

  private var stageName = ""

  private val stages = new HashMap[String, ListBuffer[CriterionTmp]]()

  private var selectedUser: User = null
  private var selectedRole = Role.investigator
  private val actualRights = new HashSet[(User, TrialRight)]()

  private val randomizationMethods = randomizationPluginManager.getPluginNames

  private val randomizationMethodSelect = {
    randomizationMethods map {
      s => (s, s)
    } toSeq
  }
  private var randomizationMethodTmp = generateEmptyRandomizationMethodConfig(randomizationMethods.head)

  private var trialSiteStratificationStatus = StratifiedTrialSite.NO.toString

  private def createCriterionsList(criterions: ListBuffer[CriterionTmp]): List[Criterion[Any, Constraint[Any]]] = {
    val result = ListBuffer[Criterion[Any, Constraint[Any]]]()

    criterions.foreach(criterionTmp => (criterionTmp.typ match {
      case "DateCriterion" => DateCriterion(id = criterionTmp.id, version = criterionTmp.version, name = criterionTmp.name, description = criterionTmp.description, inclusionConstraint = createInclusionConstraint(criterionTmp), strata = createStrata(criterionTmp))
      case "IntegerCriterion" => IntegerCriterion(id = criterionTmp.id, version = criterionTmp.version, name = criterionTmp.name, description = criterionTmp.description, inclusionConstraint = createInclusionConstraint(criterionTmp), strata = createStrata(criterionTmp))
      case "DoubleCriterion" => DoubleCriterion(id = criterionTmp.id, version = criterionTmp.version, name = criterionTmp.name, description = criterionTmp.description, inclusionConstraint = createInclusionConstraint(criterionTmp), strata = createStrata(criterionTmp))
      case "FreeTextCriterion" => FreeTextCriterion(id = criterionTmp.id, version = criterionTmp.version, name = criterionTmp.name, description = criterionTmp.description, inclusionConstraint = createInclusionConstraint(criterionTmp), strata = createStrata(criterionTmp))
      case "OrdinalCriterion" => OrdinalCriterion(id = criterionTmp.id, version = criterionTmp.version, name = criterionTmp.name, description = criterionTmp.description, values = criterionTmp.values.get.toSet, inclusionConstraint = createInclusionConstraint(criterionTmp), strata = createStrata(criterionTmp))
    }).asInstanceOf[ValidationNEL[String, Criterion[Any, Constraint[Any]]]].either match {
      case Left(x) => S.error(x.toString()) //TODO error handling
      case Right(criterion) => result += criterion
    }
    )

    result.toList
  }

  private def createInclusionConstraint[T](criterionTmp: CriterionTmp): Option[T] = {
    if (criterionTmp.inclusionConstraint.isDefined) {
      createConstraint(criterionTmp, criterionTmp.inclusionConstraint.get)
    } else {
      None
    }
  }

  private def createStrata[T <: Constraint[Any]](criterionTmp: CriterionTmp): List[T] = {
    val list: List[T] = criterionTmp.strata.toList.
      map(constraintTmp => createConstraint(criterionTmp, constraintTmp).asInstanceOf[Option[T]]).
      filter(elem => elem.isDefined).map(elem => elem.get)
    list
  }


  private def createConstraint[T](criterionTmp: CriterionTmp, constraint: ConstraintTmp): Option[T] = {
    criterionTmp.typ match {
      case "DateCriterion" => {
        val min = if (constraint.minValue.isDefined) Some(new LocalDate(Utility.slashDate.parse(constraint.minValue.get).getTime)) else None
        val max = if (constraint.maxValue.isDefined) Some(new LocalDate(Utility.slashDate.parse(constraint.maxValue.get).getTime)) else None
        Some(DateConstraint(constraint.id, constraint.version, List(min, max)).toOption.get.asInstanceOf[T])
      }
      case "IntegerCriterion" => {
        val min = if (constraint.minValue.isDefined) Some(constraint.minValue.get.toInt) else None
        val max = if (constraint.maxValue.isDefined) Some(constraint.maxValue.get.toInt) else None
        Some(IntegerConstraint(constraint.id, constraint.version, List(min, max)).toOption.get.asInstanceOf[T])
      }
      case "DoubleCriterion" => {
        val min = if (constraint.minValue.isDefined) Some(constraint.minValue.get.toDouble) else None
        val max = if (constraint.maxValue.isDefined) Some(constraint.maxValue.get.toDouble) else None
        Some(DoubleConstraint(constraint.id, constraint.version, List(min, max)).toOption.get.asInstanceOf[T])
      }
      case "FreeTextCriterion" => None
      case "OrdinalCriterion" => {
        Some(OrdinalConstraint(constraint.id, constraint.version, constraint.ordinalValues.toList.filter(entry => entry._1).map(entry => Some(entry._2))).toOption.get.asInstanceOf[T])
      }
      case _ => None
    }
  }

  private def createTreatmentArms(arms: ListBuffer[TreatmentArmTmp]): List[TreatmentArm] = {
    val result = ListBuffer[TreatmentArm]()

    arms.foreach(arm =>
      TreatmentArm(id = arm.id, version = arm.version, name = arm.name, description = arm.description, plannedSize = arm.plannedSize).either match {
        case Left(x) => S.error(x.toString()) //TODO error handling
        case Right(treatmentArm) => result += treatmentArm
      }
    )

    result.toList
  }

  private def createStages(actStages: HashMap[String, ListBuffer[CriterionTmp]]): Map[String, List[Criterion[Any, Constraint[Any]]]] = {
    val result = new HashMap[String, List[Criterion[Any, Constraint[Any]]]]()

    actStages.foreach(entry => result.put(entry._1, createCriterionsList(entry._2)))

    result.toMap
  }

  private def create(xhtml: NodeSeq): NodeSeq = {


    def save() {
      //TODO validate
      Trial(name = name, abbreviation = abbreviation, description = description, startDate = startDate, endDate = endDate, stratifyTrialSite = StratifiedTrialSite.withName(trialSiteStratificationStatus), status = TrialStatus.IN_PREPARATION, treatmentArms = createTreatmentArms(armsTmp), criterions = createCriterionsList(criterionsTmp), participatingSites = participatingSites.toList, randomizationMethod = None, stages = createStages(stages), identificationCreationType = TrialSubjectIdentificationCreationType.withName(identificationCreationTypeTmp)).either match {
        case Left(x) => S.error("trialMsg", x.toString)
        case Right(trial) => {
          //TODO Random Config
          val randomMethod = randomizationPluginManager.getPlugin(randomizationMethodTmp.name).get.randomizationMethod(new MersenneTwister(), trial, randomizationMethodTmp.getConfigurationProperties).toOption.get
          val trialWithMethod = trial.copy(randomizationMethod = Some(randomMethod))
          trialService.create(trialWithMethod, principleInvestigator).either match {
            case Left(x) => S.error("trialMsg", x)
            case Right(b) => {
              cleanVariables()
              updateCurrentUser
              S.notice("Thanks trial \"" + name + "\" saved!")
              S.redirectTo("/trial/list")
            }
          }
        }
      }

    }

    generateForm(xhtml, save())

  }


  private def edit(xhtml: NodeSeq): NodeSeq = {

    setFields()

    def save() {
      val trial = CurrentTrial.get.get
      val randomMethod = randomizationPluginManager.getPlugin(randomizationMethodTmp.name).get.randomizationMethod(new MersenneTwister(), trial, randomizationMethodTmp.getConfigurationProperties).toOption.get
      val actTrial = trial.copy(name = name, abbreviation = abbreviation, description = description, startDate = startDate, endDate = endDate, status = TrialStatus.withName(trialStatusTmp), treatmentArms = createTreatmentArms(armsTmp), criterions = createCriterionsList(criterionsTmp), participatingSites = participatingSites.toList, stages = createStages(stages), identificationCreationType = TrialSubjectIdentificationCreationType.withName(identificationCreationTypeTmp), stratifyTrialSite = StratifiedTrialSite.withName(trialSiteStratificationStatus), randomizationMethod = Some(randomMethod))
      trialService.update(actTrial)
      redirectTo("/trial/list")
    }

    generateForm(xhtml, save())
  }


  private def editStatus(xhtml: NodeSeq): NodeSeq = {
    val trial = CurrentTrial.get.get
    setFields()

    def save() {
      val actTrial = trial.copy(status = TrialStatus.withName(trialStatusTmp))
      trialService.update(actTrial)
      redirectTo("/trial/list")
    }

    bind("trial", xhtml,
      "status" -> {
        if (trial.status == TrialStatus.ACTIVE) {
          trialStatusTmp = TrialStatus.PAUSED.toString
          ajaxSelect(Seq((TrialStatus.PAUSED.toString, TrialStatus.PAUSED.toString), (TrialStatus.FINISHED.toString, TrialStatus.FINISHED.toString)), Full(trialStatusTmp), trialStatusTmp = _)
        } else if (trial.status == TrialStatus.PAUSED) {
          trialStatusTmp = TrialStatus.ACTIVE.toString
          ajaxSelect(Seq((TrialStatus.ACTIVE.toString, TrialStatus.ACTIVE.toString), (TrialStatus.FINISHED.toString, TrialStatus.FINISHED.toString)), Full(trialStatusTmp), trialStatusTmp = _)
        } else {
          <span>change not possible</span>
        }
      },
      "submit" -> submit("save", save _)
    )
  }


  private def editUsers(xhtml: NodeSeq): NodeSeq = {
    val trial = CurrentTrial.get.getOrElse {
      redirectTo("/trial/list")
    }

    val allUsers = userService.getAll.either match {
      case Left(failure) => return <div>
        {failure}
      </div>
      case Right(users) => users

    }

    selectedUser = if (allUsers.isEmpty) null else allUsers.head

    val allUsersTrial = DependencyFactory.userService.getAllFromTrial(trial).either match {
      case Left(failure) => return <div>
        {failure}
      </div>
      case Right(users) => users

    }

    actualRights.clear()

    allUsersTrial.foreach(user => {
      val actUser = allUsers.find(actUser => actUser.id == user.id).get
      actUser.rights.foreach(right => {
        if (right.trial.id == trial.id)
          actualRights.add((actUser, TrialRight(right.role, trial).toOption.get))
      })
    })

    val rightsBefore = actualRights.toList
    def save() {

      val newRights = actualRights.toList.filter(actRight => !rightsBefore.contains(actRight))
      val removedRights = rightsBefore.filter(right => !actualRights.contains(right))

      newRights.foreach(userRight => userService.addTrialRight(userRight._1.id, userRight._2))
      removedRights.foreach(userRight => userService.removeTrialRight(userRight._1.id, userRight._2))
      S.notice("Saved!")
    }

    def usersSelectField: Elem = {
      if (!allUsers.isEmpty) {
        ajaxSelectObj(allUsers.map(user => (user, user.username)).toSeq, Empty, (user: User) => {
          selectedUser = user
          Replace("roles", roleField)
        }, "id" -> "possibleUsers")
      } else {
        <span id="possibleUsers">no users to select</span>
      }
    }

    def roleField: Elem = {
      if (!allUsers.isEmpty) {
        val roles = Role.values.map(role => (role, role.toString)).toList.sortWith((elem1, elem2) => elem1._2.compareTo(elem2._2) > 0)
        selectedRole = roles.head._1
        ajaxSelectObj(roles, Empty, (role: Role.Value) => selectedRole = role, "id" -> "roles")
      } else {
        <span id="roles"></span>
      }
    }

    def rights: Elem = {
      <table id="rights" class="randi2Table">
        <thead>
          <tr>
            <th>User</th>
            <th>Role</th>
            <th></th>
          </tr>
        </thead>{if (!actualRights.isEmpty) {
        <tfoot></tfoot>
      } else {
        <tfoot>
          <tr>
            <td rowspan="3"></td>
            No users defined</tr>
        </tfoot>
      }}<tbody>
        {actualRights.toList.sortWith((elem1, elem2) => (elem1._1.username + elem1._2.role.toString).compareTo((elem2._1.username + elem2._2.role.toString)) < 0).flatMap(entry => {
          <tr>
            <td>
              {entry._1.username}
            </td>
            <td>
              {entry._2.role.toString}
            </td>
            <td>
              {ajaxButton("remove", () => {
              actualRights.remove(entry)
              Replace("rights", rights)
            })}
            </td>
          </tr>
        }
        )}
      </tbody>
      </table>
    }

    bind("trial", xhtml,
      "userSelect" -> usersSelectField,
      "roleSelect" -> roleField,
      "addRight" -> ajaxButton("add", () => {
        actualRights.add((selectedUser, TrialRight(selectedRole, trial).toOption.get))
        Replace("rights", rights)
      }),
      "rights" -> rights,
      "submit" -> submit("save", save _)
    )
  }


  private def generateForm(xhtml: NodeSeq, code: => Unit): NodeSeq = {
    def nameField(failure: Boolean = false): Elem = {
      val id = "name"
      generateEntry(id, failure, {
        ajaxText(name, v => {
          name = v
          Trial.check(name = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", nameField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", nameField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def abbreviationField(failure: Boolean = false): Elem = {
      val id = "abbreviation"
      generateEntry(id, failure, {
        ajaxText(abbreviation, v => {
          abbreviation = v
          Trial.check(abbreviation = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", abbreviationField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", abbreviationField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def descriptionField(failure: Boolean = false): Elem = {
      val id = "description"
      generateEntry(id, failure, {
        ajaxTextarea(description, v => {
          description = v
          Trial.check(description = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", descriptionField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", descriptionField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def startDateField(failure: Boolean = false): Elem = {
      val id = "startDate"
      generateEntry(id, failure, {

        text(Utility.slashDate.format(startDate.toDate).toString, v => {
          startDate = new LocalDate(Utility.slashDate.parse(v).getTime)
          Trial.check(startDate = startDate).either match {
            case Left(x) => showErrorMessage(id, x)
            case Right(_) => clearErrorMessage(id)
          }
        }, "id" -> id)
      }
      )
    }

    def endDateField(failure: Boolean = false): Elem = {
      val id = "endDate"
      generateEntry(id, failure, {
        text(Utility.slashDate.format(endDate.toDate).toString, v => {
          endDate = new LocalDate(Utility.slashDate.parse(v).getTime)
          Trial.check(endDate = endDate).either match {
            case Left(x) => showErrorMessage(id, x)
            case Right(_) => clearErrorMessage(id)
          }
        }, "id" -> id)
      }
      )
    }

    def trialSiteField: Elem = {
      val id = "trialSite"
      generateEntry(id, false, {
        ajaxSelectObj(trialSites, Empty, (trialSite: TrialSite) => {
          actualTrialSite = trialSite
          Replace("principalInvestigatorLi", principalInvestigatorField)
        })
      })
    }

    def principalInvestigatorField: Elem = {
      val principleInvestigators = userService.getAll.either match {
        case Left(x) => S.error(x); return null //TODO error handling
        case Right(list) => if (actualTrialSite != null) list.filter(user => user.site.id == actualTrialSite.id) else Nil
      }
      //TODO Service db
      val id = "principalInvestigator"
      generateEntry(id, false, {
        if (principleInvestigators.isEmpty) {
          <span>No user at this trial site</span>
        } else {
          val first = principleInvestigators.head

          untrustedSelect(principleInvestigators.map(pInvestigator => (pInvestigator.id.toString, pInvestigator.lastName)), Full(first.id.toString), setPrincipleInvestigator(_), "id" -> id)
        }
      })
    }

    def participatedSitesTable: NodeSeq = {
      <div id="participatedTrialSiteTable">
        <table width="90%">
          <tr>
            <th>Name</th>
            <th>Street</th>
            <th>Post code</th>
            <th>City</th>
            <th>Country</th>
            <th></th>
          </tr>{participatingSites.toList.sortWith((e1, e2) => e1.name.compareTo(e2.name) < 0).flatMap(trialSite => <tr>
          <td>
            {trialSite.name}
          </td>
          <td>
            {trialSite.street}
          </td>
          <td>
            {trialSite.postCode}
          </td>
          <td>
            {trialSite.city}
          </td>
          <td>
            {trialSite.country}
          </td>
          <td>
            {ajaxButton(Text("remove"), () => {
            participatingSites.remove(trialSite)
            Replace("participatedTrialSiteTable", participatedSitesTable)
          })}
          </td>
        </tr>)}
        </table>
      </div>

    }


    def randomizationMethodSelectField: NodeSeq = {
      ajaxSelect(randomizationMethodSelect, Empty, v => {
        randomizationMethodTmp = generateEmptyRandomizationMethodConfig(v)
        Replace("randomizationConfig", generateRandomizationConfigField)
      })
    }

    bind("trial", xhtml,
      "name" -> nameField(),
      "abbreviation" -> abbreviationField(),
      "description" -> descriptionField(),
      "startDate" -> startDateField(),
      "endDate" -> endDateField(),
      "trialSite" -> trialSiteField,
      "pInvestigator" -> principalInvestigatorField,
      "status" -> ajaxSelect(TrialStatus.values.map(value => (value.toString, value.toString)).toSeq, Full(trialStatusTmp), trialStatusTmp = _),
      "participatingSiteSelect" -> ajaxSelectObj(trialSites, Empty, (trialSite: TrialSite) => participatingSiteTmp = trialSite, "id" -> "participatingSite"),
      "addParticipatingSite" -> ajaxButton(Text("add"), () => {
        participatingSites += participatingSiteTmp
        Replace("participatedTrialSiteTable", participatedSitesTable)
      }),
      "pSites" -> participatedSitesTable,
      "treatmentArmName" -> ajaxText(nameNewTreatmentArm, nameNewTreatmentArm = _, "id" -> "nameNewTreatmentArm"),
      "treatmentArmDescription" -> ajaxText(descriptionNewTreatmentArm, descriptionNewTreatmentArm = _),
      "treatmentArmPlannedSubjects" -> ajaxText(plannedSubjectSizeNewTreatmentArm.toString, size => plannedSubjectSizeNewTreatmentArm = size.toInt),
      "addTreatmentArm" -> ajaxButton("add", () => {
        armsTmp += new TreatmentArmTmp(Int.MinValue, 0, "", "", 0)
        Replace("treatmentArms", generateTreatmentArms(xhtml))
      }),
      "treatmentArms" -> generateTreatmentArms(xhtml),
      //TODO selectElem
      "identificationCreationTypeSelect" -> ajaxSelect(TrialSubjectIdentificationCreationType.values.map(value => (value.toString, value.toString)).toSeq, Full(identificationCreationTypeTmp), identificationCreationTypeTmp = _),
      "criterionSelect" -> ajaxSelect(criterionTypes, Empty, criterionTypeTmp = _),
      "addSelectedCriterion" -> ajaxButton("add", () => {
        addSelectedCriterion(criterionTypeTmp, criterionsTmp)
        Replace("criterions", generateCriterions(xhtml))
      }),
      "criterions" -> generateCriterions(xhtml),
      //      "stageName" -> ajaxText(stageName, stageName = _),
      //      "addStage" -> ajaxButton("add", () => {
      //        stages.put(stageName, new ListBuffer())
      //        Replace("stagesTabs", generateStages(xhtml))
      //      }),
      //      "stages" -> generateStages(xhtml),
      "randomizationMethodSelect" -> randomizationMethodSelectField,
      "randomizationConfig" -> generateRandomizationConfigField,
      "submit" -> submit("save", code _)
    )
  }

  private def generateRandomizationConfigField: Elem = {
    <div id="randomizationConfig">
      <fieldset>
        <legend>General informations</legend>
        <ul>
          <li>
            <label for="randomizationMethodName">Name:
            </label>
            <span id="randomizationMethodName">
              {randomizationMethodTmp.name}
            </span>
          </li>
          <li>
            <label for="randomizationMethodDescription">Description:
            </label>
            <span id="randomizationMethodDescription">
              {randomizationMethodTmp.description}
            </span>
          </li>
        </ul>
      </fieldset>{if (!randomizationMethodTmp.configurationEntries.isEmpty) {
      <fieldset>
        <legend>Configurations</legend>
        <ul>
          {randomizationMethodTmp.configurationEntries.flatMap(configuration => {
          <li>
            <label for={configuration.configurationType.name}>
              {configuration.configurationType.name}
              :
              <span class="tooltip">
                <img src="/images/icons/help16.png" alt={configuration.configurationType.description} title={configuration.configurationType.description}/> <span class="info">
                {configuration.configurationType.description}
              </span>
              </span>
            </label>{ajaxText(configuration.value.toString, v => {
            //TODO check value
            configuration.value = v
          }, "id" -> configuration.configurationType.name)}
          </li>
        })}
        </ul>
      </fieldset>
    } else <div></div>}{if (randomizationMethodTmp.canBeUsedWithStratification) {
      val criterionList = criterionsTmp
      <div>
        <h3>Stratification:</h3>
        Trial site stratification: {ajaxSelect(StratifiedTrialSite.values.map(value => (value.toString, value.toString)).toSeq, Full(trialSiteStratificationStatus), trialSiteStratificationStatus = _)}
        {val result = new ListBuffer[Node]()
      for (i <- criterionList.indices) {
        val criterion = criterionList(i)
        result += generateStratumConfig("stratum-" + criterion.name, criterion)
      }
      NodeSeq fromSeq result}
      </div>

    } else <div></div>}

    </div>
  }

  private def generateStratumConfig(id: String, criterion: CriterionTmp): Elem = {
    <div class="singleField" id={id}>
      <fieldset>
        <legend>
          {criterion.typ}
        </legend>
        <ul>
          <li>
            <label>Name</label>{criterion.name}
          </li>
          <li>
            <label>Description</label>{criterion.description}
          </li>
        </ul>
        <div>
          {ajaxButton("add stratum", () => {
          val constraint = new ConstraintTmp()
          if (criterion.typ == "OrdinalCriterion") {
            constraint.ordinalValues.clear()
            criterion.values.get.foreach(value => {
              constraint.ordinalValues.add((false, value))
            })
          }
          criterion.strata.append(constraint)
          Replace(id, generateStratumConfig(id, criterion))
        })}
          {ajaxButton("remove stratum", () => {
          criterion.strata.remove(criterion.strata.size-1)
          Replace(id, generateStratumConfig(id, criterion))
        })}
        </div>{val result = new ListBuffer[Node]()
      for (i <- criterion.strata.indices) {
        val constraint = criterion.strata(i)
        result += <div class="singleField">
          {//TODO stratum configuration
          generateStratumElement(id + i, criterion, constraint)}
        </div>
      }
      NodeSeq fromSeq result}
      </fieldset>
    </div>
  }

  private def generateStratumElement(id: String, criterion: CriterionTmp, constraint: ConstraintTmp): Elem = {
    <fieldset id={id} class="inclusionConstraint">
      <legend>Constraint</legend>{if (criterion.typ != "OrdinalCriterion") {
      <ul>
        <li>
          {ajaxCheckbox(constraint.minValue.isDefined, v => {
          if (!v) {
            constraint.minValue = None
          } else {
            constraint.minValue = Some("")
          }
          Replace(id, generateStratumElement(id, criterion, constraint))
        }, "style" -> "width: 20px;")}
          lower boundary?
          {if (constraint.minValue.isDefined) {
          ajaxText(constraint.minValue.get, v => {
            constraint.minValue = Some(v)
          })
        }}
        </li>
        <li>
          {ajaxCheckbox(constraint.maxValue.isDefined, v => {
          if (!v) {
            constraint.maxValue = None
          } else {
            constraint.maxValue = Some("")
          }
          Replace(id, generateStratumElement(id, criterion, constraint))
        }, "style" -> "width: 20px;")}
          upper boundary?
          {if (constraint.maxValue.isDefined) {
          ajaxText(constraint.maxValue.get, v => {
            constraint.maxValue = Some(v)
          })
        }}
        </li>
      </ul>
    } else {
      val ordinalValues = constraint.ordinalValues
      ordinalValues.toList.sortWith((elem1, elem2) => elem1._2.compareTo(elem2._2) < 0).flatMap(value => {
        <div>
          {ajaxCheckbox(value._1, v => {
          ordinalValues.remove(value)
          ordinalValues.add((v, value._2))
          Replace(id, generateStratumElement(id, criterion, constraint))
        })}<span>
          {value._2}
        </span>
        </div>
      })
    }}
    </fieldset>
  }

  private def addSelectedCriterion(criterionType: String, criterionList: ListBuffer[CriterionTmp]) {
    def emptyValues = {
      val list = new ListBuffer[String]()
      list += ""
      list += ""
      Some(list)
    }
    criterionType match {
      case "OrdinalCriterion" => criterionList += new CriterionTmp(Int.MinValue, 0, "OrdinalCriterion", "", "", emptyValues, None)
      case x => criterionList += new CriterionTmp(Int.MinValue, 0, x, "", "", None, None)
    }
  }
                                                                                                                           19

  private def setPrincipleInvestigator(id: String) {
    principleInvestigator = userService.get(id.toInt).either match {
      case Left(failure) => S.error("trialMsg", failure); null
      case Right(user) => user.get
    }

  }

  private def cleanVariables() {
    name = ""
    abbreviation = ""
    stageCount = 1
    description = ""
    startDate = new LocalDate
    endDate = new LocalDate
    actualTrialSite = null
    trialSites = trialSiteService.getAll.toOption.get.map(trialSite => (trialSite, trialSite.name)) //TODO Failure handling
    principleInvestigator = null
    participatingSiteTmp = trialSites.sortWith((e1, e2) => e1._2.compareTo(e2._2) > 0).head._1
    //TODO clean participation sites
    armsTmp.clear()
    armsTmp += new TreatmentArmTmp(Int.MinValue, 0, "", "", 0)
    criterionsTmp.clear()
    cleanTreatmentArmVariables()
  }

  private def cleanTreatmentArmVariables() {
    nameNewTreatmentArm = ""
    descriptionNewTreatmentArm = ""
    plannedSubjectSizeNewTreatmentArm = 0
  }

  private def setFields() {
    val trial = CurrentTrial.get.get
    name = trial.name
    abbreviation = trial.abbreviation
    description = trial.description
    startDate = trial.startDate
    endDate = trial.endDate
    actualTrialSite = null
    trialSites = trialSiteService.getAll.toOption.get.map(trialSite => (trialSite, trialSite.name)) //TODO error handling
    principleInvestigator = null
    participatingSiteTmp =
      if (trialSites.size > 1) {
        trialSites.sortWith((e1, e2) => e1._2.compareTo(e2._2) > 0)(0)._1
      } else {
        trialSites(0)._1
      }

    participatingSites.clear()
    trial.participatingSites.foreach(site => participatingSites.add(site))


    armsTmp.clear()
    trial.treatmentArms.foreach(arm => armsTmp.append(new TreatmentArmTmp(arm.id, arm.version, arm.name, arm.description, arm.plannedSize)))


    trialStatusTmp = trial.status.toString

    identificationCreationTypeTmp = trial.identificationCreationType.toString

    trialSiteStratificationStatus = trial.stratifyTrialSite.toString

    criterionsTmp.clear()
    trial.criterions.foreach {
      criterion =>
        if (criterion.isInstanceOf[OrdinalCriterion]) {
          val values = new ListBuffer[String]()
          criterion.asInstanceOf[OrdinalCriterion].values.foreach(s => values += s)
          criterionsTmp += new CriterionTmp(criterion.id, criterion.version, "OrdinalCriterion", criterion.name, criterion.description, Some(values), getInclusionConstraintTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]), getStrataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]))
        } else if (criterion.isInstanceOf[DateCriterion])
          criterionsTmp += new CriterionTmp(criterion.id, criterion.version, "DateCriterion", criterion.name, criterion.description, None, getInclusionConstraintTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]), getStrataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]))
        else if (criterion.isInstanceOf[IntegerCriterion])
          criterionsTmp += new CriterionTmp(criterion.id, criterion.version, "IntegerCriterion", criterion.name, criterion.description, None, getInclusionConstraintTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]), getStrataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]))
        else if (criterion.isInstanceOf[DoubleCriterion])
          criterionsTmp += new CriterionTmp(criterion.id, criterion.version, "DoubleCriterion", criterion.name, criterion.description, None, getInclusionConstraintTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]), getStrataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]))
        else if (criterion.isInstanceOf[FreeTextCriterion])
          criterionsTmp += new CriterionTmp(criterion.id, criterion.version, "FreeTextCriterion", criterion.name, criterion.description, None, getInclusionConstraintTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]), getStrataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]]))
    }

    randomizationMethodTmp = generateRandomizationMethodConfig(trial.randomizationMethod)

  }

  private def getInclusionConstraintTmp(crit: Criterion[Any, Constraint[Any]]): Option[ConstraintTmp] = {
    if (crit.inclusionConstraint.isDefined) {

      getConstraintTmp(crit.inclusionConstraint.get, crit)

    } else None
  }

  private def getStrataTmp(crit: Criterion[Any, Constraint[Any]]): ListBuffer[ConstraintTmp] = {
    val result = new ListBuffer[ConstraintTmp]()
    crit.strata.foreach(constraint => {
      val constrTmp = getConstraintTmp(constraint, crit)
      if (constrTmp.isDefined) result.append(constrTmp.get)
    })
    result
  }

  private def getConstraintTmp(constraint: Constraint[Any], crit: Criterion[Any, Constraint[Any]]): Option[ConstraintTmp] = {
    if (constraint.isInstanceOf[OrdinalConstraint]) {
      val actConstraint = constraint.asInstanceOf[OrdinalConstraint]
      val values = new mutable.HashSet[(Boolean, String)]()
      actConstraint.expectedValues.foreach(element => {
       values.add(true, element)
      })
      val actCriterion = crit.asInstanceOf[OrdinalCriterion]
      actCriterion.values.foreach(value => {
        if(!values.map(elem =>elem._2).contains(value))
          values.add(false, value)
      })
      Some(new ConstraintTmp(id = actConstraint.id, version = actConstraint.version, ordinalValues = values))

    } else if (constraint.isInstanceOf[IntegerConstraint]) {
      val actConstraint = constraint.asInstanceOf[IntegerConstraint]
      val firstValue = actConstraint.firstValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      val secondValue = actConstraint.secondValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      Some(new ConstraintTmp(id = actConstraint.id, version = actConstraint.version, minValue = firstValue, maxValue = secondValue))

    } else if (constraint.isInstanceOf[DoubleConstraint]) {
      val actConstraint = constraint.asInstanceOf[DoubleConstraint]
      val firstValue = actConstraint.firstValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      val secondValue = actConstraint.secondValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      Some(new ConstraintTmp(id = actConstraint.id, version = actConstraint.version, minValue = firstValue, maxValue = secondValue))

    } else if (constraint.isInstanceOf[DateConstraint]) {
      val actConstraint = constraint.asInstanceOf[DateConstraint]
      val firstValue = actConstraint.firstValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      val secondValue = actConstraint.secondValue match {
        case None => None
        case Some(value) => Some(value.toString)
      }
      Some(new ConstraintTmp(id = actConstraint.id, version = actConstraint.version, minValue = firstValue, maxValue = secondValue))

    } else None
  }

  private def showParticipatedSites(in: NodeSeq): NodeSeq = {
    <div id="participatedTrialSiteTable">
      <table width="90%">
        <tr>
          <th>Name</th>
          <th>Street</th>
          <th>Post code</th>
          <th>City</th>
          <th>Country</th>
          <th></th>
        </tr>{participatingSites.toList.sortWith((e1, e2) => e1.name.compareTo(e2.name) < 0).flatMap(trialSite => <tr>
        <td>
          {trialSite.name}
        </td>
        <td>
          {trialSite.street}
        </td>
        <td>
          {trialSite.postCode}
        </td>
        <td>
          {trialSite.city}
        </td>
        <td>
          {trialSite.country}
        </td>
        <td>
          {ajaxButton(Text("remove"), () => {
          participatingSites.remove(trialSite)
          SetHtml("participatedTrialSiteTable", showParticipatedSites(in))
        })}
        </td>
      </tr>)}
      </table>
    </div>

  }

  private def generateTreatmentArms(xhtml: NodeSeq): NodeSeq = {
    <div id="treatmentArms">
      {val result = new ListBuffer[Node]()
    for (i <- armsTmp.indices) {
      val arm = armsTmp(i)
      result += <div class="singleField">
        <fieldset>
          <legend>Treatment arm
            {ajaxButton(<img alt="remove" src="/images/icons/error16.png"/>, () => {
            armsTmp.remove(i)
            Replace("treatmentArms", generateTreatmentArms(xhtml))
          })}
          </legend>
          <ul>
            <li>
              <label for={"armName" + i}>Name</label>{ajaxText(arm.name, arm.name = _, "id" -> ("armName" + i))}
            </li>
            <li>
              <label for={"armDescription" + i}>Description</label>{ajaxTextarea(arm.description, arm.description = _, "id" -> ("armDescription" + i))}
            </li>
            <li>
              <label for={"armPlannedSize" + i}>Planned size</label>{ajaxText(arm.plannedSize.toString, (v) => arm.plannedSize = v.toInt, "id" -> ("armPlannedSize" + i)) /*TODO check toInt */}
            </li>
          </ul>
        </fieldset>
      </div>
    }
    NodeSeq fromSeq result}
    </div>
  }


  private def generateGeneralCriterions(xhtml: NodeSeq, id: String, criterionList: ListBuffer[CriterionTmp]): NodeSeq = {
    <div id={id}>
      {val result = new ListBuffer[Node]()
    for (i <- criterionList.indices) {
      val criterion = criterionList(i)
      result += <div class="singleField">
        <fieldset>
          <legend>
            {criterion.typ}{ajaxButton(<img alt="remove" src="/images/icons/error16.png"/>, () => {
            criterionList.remove(i)
            Replace(id, generateGeneralCriterions(xhtml, id, criterionList))
          })}
          </legend>
          <ul>
            <li>
              <label for={id + "Name" + i}>Name</label>{ajaxText(criterion.name, criterion.name = _, "id" -> (id + "Name" + i))}
            </li>
            <li>
              <label for={id + "Description" + +i}>Description</label>{ajaxTextarea(criterion.description, criterion.description = _, "id" -> (id + "Description" + +i))}
            </li>{createValues(criterion, xhtml, id, criterionList)}
          </ul>{if (criterion.typ != "FreeTextCriterion") generateInclusionConstraint(xhtml, "inclusionConstraintFieldset" + i, criterion)}

        </fieldset>
      </div>
    }
    NodeSeq fromSeq result}
    </div>
  }

  private def generateCriterions(xhtml: NodeSeq): NodeSeq = {
    generateGeneralCriterions(xhtml, "criterions", criterionsTmp)
  }

  private def generateInclusionConstraint(xhtml: NodeSeq, id: String, criterion: CriterionTmp): NodeSeq = {

    <fieldset id={id} class="inclusionConstraint">
      <legend>Inclusion constraint
        {ajaxCheckbox(criterion.inclusionConstraint.isDefined, v => {
        if (!v) criterion.inclusionConstraint = None
        else {
          criterion.inclusionConstraint = Some(new ConstraintTmp())
          if (criterion.typ == "OrdinalCriterion") {
            criterion.inclusionConstraint.get.ordinalValues.clear()
            criterion.values.get.foreach(value => {
              criterion.inclusionConstraint.get.ordinalValues.add((false, value))
            })
          }
        }
        Replace(id, generateInclusionConstraint(xhtml, id, criterion))
      }, "style" -> "width: 20px;")}
      </legend>{if (criterion.inclusionConstraint.isDefined) {
      if (criterion.typ != "OrdinalCriterion") {
        <ul>
          <li>
            {ajaxCheckbox(criterion.inclusionConstraint.get.minValue.isDefined, v => {
            if (!v) {
              criterion.inclusionConstraint.get.minValue = None
            } else {
              criterion.inclusionConstraint.get.minValue = Some("")
            }
            Replace(id, generateInclusionConstraint(xhtml, id, criterion))
          }, "style" -> "width: 20px;")}
            lower boundary?
            {if (criterion.inclusionConstraint.get.minValue.isDefined) {
            ajaxText(criterion.inclusionConstraint.get.minValue.get, v => {
              criterion.inclusionConstraint.get.minValue = Some(v)
            })
          }}
          </li>
          <li>
            {ajaxCheckbox(criterion.inclusionConstraint.get.maxValue.isDefined, v => {
            if (!v) {
              criterion.inclusionConstraint.get.maxValue = None
            } else {
              criterion.inclusionConstraint.get.maxValue = Some("")
            }
            Replace(id, generateInclusionConstraint(xhtml, id, criterion))
          }, "style" -> "width: 20px;")}
            upper boundary?
            {if (criterion.inclusionConstraint.get.maxValue.isDefined) {
            ajaxText(criterion.inclusionConstraint.get.maxValue.get, v => {
              criterion.inclusionConstraint.get.maxValue = Some(v)
            })
          }}
          </li>
        </ul>
      } else {
        val ordinalValues = criterion.inclusionConstraint.get.ordinalValues
        ordinalValues.toList.sortWith((elem1, elem2) => elem1._2.compareTo(elem2._2) < 0).flatMap(value => {
          <div>
            {ajaxCheckbox(value._1, v => {
            ordinalValues.remove(value)
            ordinalValues.add((v, value._2))
            Replace(id, generateInclusionConstraint(xhtml, id, criterion))
          })}<span>
            {value._2}
          </span>
          </div>
        })
      }
    } else {
      <span>No inclusion constraint</span>
    }}
    </fieldset>
  }


  private def createValues(criterion: CriterionTmp, xhtml: NodeSeq, id: String, criterionList: ListBuffer[CriterionTmp]): NodeSeq = {
    criterion.values match {
      case None => <span></span>
      case Some(x) => {
        //TODO implement specific replacement
        <li>
          <fieldset>
            <legend>Values</legend>
            <ul>
              <div>
                {ajaxButton("add element", () => {
                x += ""
                criterion.inclusionConstraint = None
                Replace(id, generateGeneralCriterions(xhtml, id, criterionList))
              })}{val result = new ListBuffer[Node]()
              for (i <- x.indices) result += <div>
                {ajaxText(x(i), v => {
                  x(i) = v
                  criterion.inclusionConstraint = None
                  Replace(id, generateGeneralCriterions(xhtml, id, criterionList))
                })}{ajaxButton("remove", () => {
                  x.remove(i)
                  criterion.inclusionConstraint = None
                  Replace(id, generateGeneralCriterions(xhtml, id, criterionList))
                })}
              </div>
              NodeSeq fromSeq result}
              </div>
            </ul>
          </fieldset>
        </li>
      }
    }
  }

  private def generateStages(xhtml: NodeSeq): NodeSeq = {
    <div id="stagesTabs">
      {val result = new ListBuffer[Node]()
    for (key <- stages.keySet.toList.sortWith((first, second) => first.compareTo(second) < 0)) {
      val stageElements = stages.get(key).get
      val id = key + "Criterions"
      var criterionType = "DateType"
      result += <div class="singleField">
        <fieldset>
          <legend>
            {key}{ajaxButton(<img alt="remove" src="/images/icons/error16.png"/>, () => {
            stages.remove(key)
            Replace("stagesTabs", generateStages(xhtml))
          })}
          </legend>
          <ul>
            <li>Please select:
              {ajaxSelect(criterionTypes, Empty, criterionType = _)}{ajaxButton("add", () => {
              addSelectedCriterion(criterionType, stageElements)
              Replace(id, generateGeneralCriterions(xhtml, id, stageElements))
            })}
            </li>
            <li>
              {generateGeneralCriterions(xhtml, id, stageElements)}
            </li>
          </ul>
        </fieldset>
      </div>
    }
    NodeSeq fromSeq result}
    </div>
  }

  private def trials(in: NodeSeq): NodeSeq = {
    trialService.getAll.either match {
      case Left(x) => <tr>
        <td colspan="8">
          {x}
        </td>
      </tr>
      case Right(trials) => {
        trials.flatMap(trial => {
          <tr>
            <td>
              {trial.abbreviation}
            </td>
            <td>
              {trial.name}
            </td>
            <td>
              {trial.status}
            </td>
            <td>
              {trial.startDate.toString(DateTimeFormat.forPattern("yyyy-MM-dd"))}
            </td>
            <td>
              {trial.endDate.toString(DateTimeFormat.forPattern("yyyy-MM-dd"))}
            </td>
            <td>
              {trial.description}
            </td>
            <td>
              {link("/trial/generalInformation", () => {
              CurrentTrial.set(Some(trialService.get(trial.id).toOption.get.get))
              subjectIdentifier = ""
              subjectDataList.clear()
            }, Text("select")) /*TODO error handling*/}
            </td>
          </tr>
        })
      }
    }
  }

  private var subjectIdentifier = ""
  private var subjectDataList = new ListBuffer[SubjectDataTmp]()

  private def randomize(in: NodeSeq): NodeSeq = {
    val trial = CurrentTrial.get.getOrElse {
      error("Trial not found")
      redirectTo("/trial/list")
    }

    if (subjectDataList.isEmpty) {
      trial.criterions.foreach(criterion => subjectDataList += new SubjectDataTmp(criterion.asInstanceOf[Criterion[Any, Constraint[Any]]], null))
    }

    val subjectDataNodeSeq = new ListBuffer[Node]()

    for (subjectData <- subjectDataList) {
      subjectDataNodeSeq +=  <fieldset> {
      // generateEntryWithInfo(subjectData.criterion.name, false, subjectData.criterion.description,
        <legend>
            <span>
              {subjectData.criterion.name}
            </span>
            <span class="tooltip">
              <img src="/images/icons/help16.png" alt={subjectData.criterion.description} title={subjectData.criterion.description}/> <span class="info">
              {subjectData.criterion.description}
            </span>
            </span>
        </legend>
          <div>
            {if (subjectData.criterion.getClass == classOf[DateCriterion]) {
            {
              ajaxText("", (y: String) => {
                if (!y.isEmpty) {
                  try {
                    val value = Utility.slashDate.parse(y)
                    subjectData.value = value
                    if (!subjectData.criterion.isValueCorrect(value))
                      S.error("randomizeMsg", subjectData.criterion.name + ": inclusion constraint not fulfilled")
                  } catch {
                    case _ => S.error("randomizeMsg", subjectData.criterion.name + ": unknown failure")
                  }
                } else {
                  S.error("randomizeMsg", subjectData.criterion.name + ": value not set")
                }
              })
            }
          } else if (subjectData.criterion.getClass == classOf[DoubleCriterion]) {
            {
              ajaxText(if (subjectData.value == null) "" else subjectData.value.toString, (y: String) => {
                if (!y.isEmpty) {
                  try {
                    val value = y.toDouble
                    subjectData.value = value
                    if (!subjectData.criterion.isValueCorrect(value))
                      S.error("randomizeMsg", subjectData.criterion.name + ": inclusion constraint not fulfilled")
                  } catch {
                    case nfe: NumberFormatException => S.error("randomizeMsg", subjectData.criterion.name + ": not a number")
                    case _ => S.error("randomizeMsg", "unknown failure")
                  }
                } else {
                  S.error("randomizeMsg", subjectData.criterion.name + ": value not set")
                }
              })
            }
          } else if (subjectData.criterion.getClass == classOf[IntegerCriterion]) {
            {
              ajaxText(if (subjectData.value == null) "" else subjectData.value.toString, (y: String) => {
                if (!y.isEmpty) {
                  try {
                    val value = y.toInt
                    subjectData.value = value
                    if (!subjectData.criterion.isValueCorrect(value))
                      S.error("randomizeMsg", subjectData.criterion.name + ": inclusion constraint not fulfilled")
                  } catch {
                    case nfe: NumberFormatException => S.error("randomizeMsg", subjectData.criterion.name + ": not a number")
                    case _ => S.error("randomizeMsg", "unknown failure")
                  }
                } else {
                  S.error("randomizeMsg", subjectData.criterion.name + ": value not set")
                }
              })
            }
          } else if (subjectData.criterion.getClass == classOf[FreeTextCriterion]) {
            {
              ajaxText(if (subjectData.value == null) "" else subjectData.value.toString, (y: String) => {
                subjectData.value = y
                if (subjectData.value == null || y.isEmpty)
                  S.error("randomizeMsg", subjectData.criterion.name + ": Element is empty")
                else if (!subjectData.criterion.isValueCorrect(y))
                  S.error("randomizeMsg", subjectData.criterion.name + ": inclusion constraint not fulfilled")
              })
            }
          } else if (subjectData.criterion.getClass == classOf[OrdinalCriterion]) {
            {
              <div>
                {ajaxRadio(subjectData.criterion.asInstanceOf[OrdinalCriterion].values.toSeq, Empty, (y: String) => {
                subjectData.value = y
                if (!subjectData.criterion.isValueCorrect(y))
                  S.error("randomizeMsg", subjectData.criterion.name + ": inclusion constraint not fulfilled")
              }).toForm}
              </div>
            }
          } else {
            <span>?</span>
          }}
          </div>
        //)
        }
      </fieldset>
    }

    def randomizeSubject() {
      val properties: List[SubjectProperty[Any]] = subjectDataList.toList.map(subjectData => SubjectProperty(criterion = subjectData.criterion, value = subjectData.value).either match {
        case Left(x) => null
        case Right(prop) => prop
      })

      if (trial.criterions.isEmpty || subjectDataList.toList.map(subjectData => subjectData.criterion.isValueCorrect(subjectData.value)).reduce((acc, elem) => acc && elem)) {
        if (trial.identificationCreationType != TrialSubjectIdentificationCreationType.EXTERNAL) subjectIdentifier = "system"
        TrialSubject(identifier = subjectIdentifier, investigatorUserName = CurrentUser.get.get.username, trialSite = CurrentUser.get.get.site, properties = properties).either match {
          case Left(x) => S.error("randomizeMsg", x.toString())
          case Right(subject) => {
            trialService.randomize(trial, subject).either match {
              case Left(x) => S.error("randomizeMsg", x)
              case Right(result) => {
                S.notice("Thanks patient (" + result._2 + ") randomized to treatment arm: " + result._1.name + "!")
                CurrentTrial.set(Some(trialService.get(trial.id).toOption.get.get))
                subjectDataList.clear()
                subjectIdentifier = ""



                val user = CurrentUser.get.get
                val rightList = user.rights.filter(right => right.trial.id == trial.id)
                val roles = rightList.map(right => right.role)

                if (roles.contains(Role.principleInvestigator) || roles.contains(Role.statistician) || roles.contains(Role.trialAdministrator) || roles.contains(Role.monitor)) {
                  S.redirectTo("/trial/randomizationData")
                } else {
                  S.redirectTo("/trial/randomizationDataInvestigator")
                }


              }
            }
          }
        }
      } else S.error("randomizeMsg", "Inclusion constraints not fulfilled")
    }

    bind("form", in,
      "identifier" -> {
        if (trial.identificationCreationType == TrialSubjectIdentificationCreationType.EXTERNAL)
          ajaxText(subjectIdentifier, s => subjectIdentifier = s)
        else <span>System generated identifier</span>
      },
      "data" -> {
        NodeSeq fromSeq subjectDataNodeSeq
      },
      "cancel" -> <a href={val user = CurrentUser.get.get
      val rightList = user.rights.filter(right => right.trial.id == trial.id)
      val roles = rightList.map(right => right.role)
      if (roles.contains(Role.principleInvestigator) || roles.contains(Role.statistician) || roles.contains(Role.trialAdministrator) || roles.contains(Role.monitor)) {
        "/trial/randomizationData"
      } else {
        "/trial/randomizationDataInvestigator"
      }}>Cancel</a>,
      "submit" -> button("Randomize", randomizeSubject _))
  }

  private def trialSubjects(in: NodeSeq): NodeSeq = {
    val trial = CurrentTrial.get.getOrElse {
      error("Trial not found")
      redirectTo("/trial/list")
    }
    var odd = true
    trial.getSubjects.flatMap(trialSubject => {
      odd = !odd
      <tr class={if (odd) "odd" else "even"}>
        <td>
          {trialSubject.createdAt.toString(DateTimeFormat.forPattern("yyyy-MM-dd HH:mm"))}
        </td>
        <td>
          {trialSubject.identifier}
        </td>
        <td>
          {if (!trialSubject.properties.isEmpty) trialSubject.properties.map(prop => prop.criterion.name + ": " + prop.value).reduce((acc, element) => acc + " | " + element)}
        </td>
        <td>
          {link("/trialSubject/show", () => selectedTrialSubject = Some(trialSubject), Text("Show"))}
        </td>
      </tr>
    })
  }

  private def showTrialSubject(in: NodeSeq): NodeSeq = {
    val trialSubject: TrialSubject = selectedTrialSubject.getOrElse {
      error("Trial subject not found")
      redirectTo("/trialSubject/list")
    }
    bind("form", in,
      "identifier" -> trialSubject.identifier,
      "back" -> <a href="/trialSubject/list">Cancel</a>)
  }

  private def confirmDelete(in: NodeSeq): NodeSeq = {
    val trial = CurrentTrial.get.getOrElse {
      error("Trial not found")
      redirectTo("/trial/list.html")
    }

    def deleteTrial() {
      notice("Trial " + (trial.name) + " deleted")
      trialService.delete(trial)
      redirectTo("/trial/list.html")
    }

    // bind the incoming XHTML to a "delete" button.
    // when the delete button is pressed, call the "deleteUser"
    // function (which is a closure and bound the "user" object
    // in the current content)
    bind("xmp", in, "name" -> (trial.name),
      "delete" -> submit("Delete", deleteTrial _))

  }

  private def generateEmptyRandomizationMethodConfig(randomizationMethodName: String): RandomizationMethodConfigTmp = {
    val plugin = randomizationPluginManager.getPlugin(randomizationMethodName).get
    val configurations = plugin.randomizationConfigurationOptions()._1
    val methodConfigsTmp = configurations.map(config => {
      if (config.getClass == classOf[BooleanConfigurationType]) {
        new RandomizationMethodConfigEntryTmp(config.asInstanceOf[BooleanConfigurationType], true)
      } else if (config.getClass == classOf[DoubleConfigurationType]) {
        new RandomizationMethodConfigEntryTmp(config.asInstanceOf[DoubleConfigurationType], 0.0)
      } else if (config.getClass == classOf[IntegerConfigurationType]) {
        new RandomizationMethodConfigEntryTmp(config.asInstanceOf[IntegerConfigurationType], 0)
      } else if (config.getClass == classOf[OrdinalConfigurationType]) {
        new RandomizationMethodConfigEntryTmp(config.asInstanceOf[OrdinalConfigurationType], config.asInstanceOf[OrdinalConfigurationType].options.head)
      }

    })
    new RandomizationMethodConfigTmp(name = plugin.i18nName, description = plugin.description, canBeUsedWithStratification = plugin.canBeUsedWithStratification, configurationEntries = methodConfigsTmp.asInstanceOf[List[RandomizationMethodConfigEntryTmp[Any]]])
  }

  private def generateRandomizationMethodConfig(randomizationMethod: Option[RandomizationMethod]): RandomizationMethodConfigTmp = {
    if (randomizationMethod.isEmpty) generateEmptyRandomizationMethodConfig(randomizationMethods.head)
    else {
      val method = randomizationMethod.get

      val plugin = randomizationPluginManager.getPluginForMethod(method).get
      val configurations = plugin.getRandomizationConfigurations(method.id)
      val methodConfigsTmp = configurations.map(configProp => {
        val config = configProp.configurationType
        if (config.getClass == classOf[BooleanConfigurationType]) {
          new RandomizationMethodConfigEntryTmp(config.asInstanceOf[BooleanConfigurationType], configProp.value)
        } else if (config.getClass == classOf[DoubleConfigurationType]) {
          new RandomizationMethodConfigEntryTmp(config.asInstanceOf[DoubleConfigurationType], configProp.value)
        } else if (config.getClass == classOf[IntegerConfigurationType]) {
          new RandomizationMethodConfigEntryTmp(config.asInstanceOf[IntegerConfigurationType], configProp.value)
        } else if (config.getClass == classOf[OrdinalConfigurationType]) {
          new RandomizationMethodConfigEntryTmp(config.asInstanceOf[OrdinalConfigurationType], configProp.value)
        }

      })
      new RandomizationMethodConfigTmp(name = plugin.i18nName, description = plugin.description, canBeUsedWithStratification = plugin.canBeUsedWithStratification, configurationEntries = methodConfigsTmp.asInstanceOf[List[RandomizationMethodConfigEntryTmp[Any]]])
    }
  }

}

case class TreatmentArmTmp(id: Int, version: Int, var name: String, var description: String, var plannedSize: Int) {}

case class CriterionTmp(id: Int, version: Int, typ: String, var name: String, var description: String, values: Option[ListBuffer[String]], var inclusionConstraint: Option[ConstraintTmp] = None, var strata: ListBuffer[ConstraintTmp] = new ListBuffer()) {}

case class ConstraintTmp(id: Int = Int.MinValue, version: Int = 0, var minValue: Option[String] = None, var maxValue: Option[String] = None, ordinalValues: HashSet[(Boolean, String)] = new HashSet())

case class SubjectDataTmp(criterion: Criterion[Any, Constraint[Any]], var value: Any) {}

case class RandomizationMethodConfigTmp(id: Int = Int.MinValue, version: Int = 0, name: String, description: String, canBeUsedWithStratification: Boolean, configurationEntries: List[RandomizationMethodConfigEntryTmp[Any]]) {

  def getConfigurationProperties: List[ConfigurationProperty[Any]] = {
    configurationEntries.map(config => {
      if (config.configurationType.getClass == classOf[BooleanConfigurationType]) {
        new ConfigurationProperty(config.configurationType, config.value.toString.toBoolean)
      } else if (config.configurationType.getClass == classOf[DoubleConfigurationType]) {
        new ConfigurationProperty(config.configurationType, config.value.toString.toDouble)
      } else if (config.configurationType.getClass == classOf[IntegerConfigurationType]) {
        new ConfigurationProperty(config.configurationType, config.value.toString.toInt)
      } else if (config.configurationType.getClass == classOf[OrdinalConfigurationType]) {
        new ConfigurationProperty(config.configurationType, config.value)
      }
    }).asInstanceOf[List[ConfigurationProperty[Any]]]
  }
}

case class RandomizationMethodConfigEntryTmp[T](configurationType: ConfigurationType[T], var value: T) {}