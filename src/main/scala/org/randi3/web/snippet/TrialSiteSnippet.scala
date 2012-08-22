package org.randi3.web.snippet

import java.util.Locale

import scala.xml._
import scala.xml.Group
import scala.xml.NodeSeq
import scala.xml.Text

import org.randi3.web.lib.DependencyFactory

import net.liftweb.common._
import net.liftweb.http.SHtml._
import net.liftweb.http.S._
import net.liftweb.http._
import js.JsCmds._

import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.liftweb._
import org.randi3.model.{User, TrialSite}
import scalaz.NonEmptyList
import scala.Right
import scalaz._
import Scalaz._
import org.randi3.web.util.CurrentUser

class TrialSiteSnippet extends StatefulSnippet {


  private var selectedTrialSite: Option[TrialSite] = None
  private var name = ""
  private var country = ""
  private var postCode = ""
  private var city = ""
  private var street = ""
  private var password = ""
  private var passwordCheck = ""


  def dispatch = {
    case "info" => redirectTo("/trialSite/list")
    case "trialSites" => trialSites _
    case "create" => create _
    case "edit" => edit _
  }

  private val trialSiteService = DependencyFactory.trialSiteService

  /**
   * Get the XHTML containing a list of users
   */
  private def trialSites(xhtml: NodeSeq): NodeSeq = {
    val currentUser = CurrentUser.getOrElse(
      redirectTo("/index")
    )
    trialSiteService.getAll.either match {
      case Left(x) => <tr>
        <td colspan="7">
          {x}
        </td>
      </tr> //TODO show Failure
      case Right(trialSites) => trialSites.flatMap(trialSite => <tr>
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
          {if (currentUser.administrator) {
          link("/trialSite/edit", () => selectedTrialSite = Some(trialSite), Text("Edit"))
        }}
        </td>
      </tr>)
    }
  }

  /**
   * Confirm deleting a user
   */
  private def confirmDelete(xhtml: Group): NodeSeq = {
    if (selectedTrialSite.isDefined) {
      val trialSite = selectedTrialSite.get
      def deleteTrialSite() {
        notice("Trial site " + (trialSite.name) + " deleted")
        trialSiteService.delete(trialSite)
        redirectTo("/trialSite/list.html")
      }

      // bind the incoming XHTML to a "delete" button.
      // when the delete button is pressed, call the "deleteUser"
      // function (which is a closure and bound the "user" object
      // in the current content)
      bind("xmp", xhtml, "name" -> (trialSite.name),
        "delete" -> submit("Delete", deleteTrialSite _))

      // if the was no ID or the user couldn't be found,
      // display an error and redirect
    } else {
      error("Trial site not found");
      redirectTo("/trialSite/list.html")
    }
  }


  private def create(xhtml: NodeSeq): NodeSeq = {

    def save() {
      TrialSite(name = name, street = street, postCode = postCode, city = city, country = country, password = password).either match {
        case Left(x) => S.error("trialSiterMsg", x.toString) //TODO set field failure
        case Right(site) => trialSiteService.create(site).either match {
          case Left(x) => S.error("trialSiteMsg", x)
          case Right(b) => {
            clearFields()
            S.notice("Thanks \"" + name + "\" saved!")
            S.redirectTo("/trialSite/list")
          }
        }
      }
    }

    generateForm(xhtml, save)
  }


  private def edit(xhtml: NodeSeq): NodeSeq = {
    if (selectedTrialSite.isDefined) {
      setFields(selectedTrialSite.get)

      def update() {
        TrialSite(id = selectedTrialSite.get.id, version = selectedTrialSite.get.version, name = name, street = street, postCode = postCode, city = city, country = country, password = password).either match {
          case Left(x) => S.error("trialSiterMsg", x.toString) //TODO set field failure
          case Right(site) => trialSiteService.update(site).either match {
            case Left(x) => S.error("trialSiteMsg", x)
            case Right(b) => {
              clearFields()
              S.notice("Thanks \"" + name + "\" saved!")
              S.redirectTo("/trialSite/list")
            }
          }
        }
      }

      generateForm(xhtml, update)
    } else S.redirectTo("/trialSite/list")

  }


  private def generateForm(xhtml: NodeSeq, code: => Unit): NodeSeq = {

    def nameField(failure: Boolean = false): Elem = {
      val id = "name"
      generateEntry(id, failure, {
        ajaxText(name, v => {
          name = v
          TrialSite.check(name = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", nameField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", nameField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def streetField(failure: Boolean = false): Elem = {
      val id = "street"
      generateEntry(id, failure, {
        ajaxText(street, v => {
          street = v
          TrialSite.check(street = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", streetField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", streetField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def postCodeField(failure: Boolean = false): Elem = {
      val id = "postCode"
      generateEntry(id, failure, {
        ajaxText(postCode, v => {
          postCode = v
          TrialSite.check(postCode = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", postCodeField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", postCodeField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def cityField(failure: Boolean = false): Elem = {
      val id = "city"
      generateEntry(id, failure, {
        ajaxText(city, v => {
          city = v
          TrialSite.check(city = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", cityField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", cityField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def countryField(failure: Boolean = false): Elem = {
      val id = "country"
      generateEntry(id, failure, {
        ajaxText(country, v => {
          country = v
          TrialSite.check(country = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", countryField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", countryField(false))
          }
        }, "id" -> id)
      }
      )
    }

    def passwordField(failure: Boolean = false): Elem = {
      val id = "password"
      generateEntry(id, failure, {
        ajaxText(password, v => {
          password = v
          TrialSite.check(password = v).either match {
            case Left(x) => showErrorMessage(id, x); Replace(id + "Li", passwordField(true))
            case Right(_) => clearErrorMessage(id); Replace(id + "Li", passwordField(false))
          }
        }, "id" -> id, "type" -> "password")
      }
      )
    }

    def passwordCheckField(failure: Boolean = false): Elem = {
      val id = "passwordCheck"
      generateEntry(id, failure, {
        ajaxText(passwordCheck, v => {
          passwordCheck = v
          if (password == passwordCheck) {
            clearErrorMessage(id)
            Replace(id + "Li", passwordCheckField(false))
          } else {
            S.error(id + "Msg", "<- passwords does not match")
            Replace(id + "Li", passwordCheckField(true))
          }
        }, "id" -> id, "type" -> "password")
      }
      )
    }

    bind("trialSite", xhtml,
      "name" -> nameField(),
      "street" -> streetField(),
      "postCode" -> postCodeField(),
      "city" -> cityField(),
      "country" -> countryField(),
      "password" -> passwordField(),
      "passwordCheck" -> passwordCheckField(),
      "submit" -> submit("save", code _)
    )
  }


  private def generateEntry(id: String, failure: Boolean, element: Elem): Elem = {
    <li id={id + "Li"} class={if (failure) "errorHint" else ""}>
      <label for={id}>
        {id}
      </label>{element}<lift:msg id={id + "Msg"} errorClass="err"/>
    </li>
  }


  private def showErrorMessage(id: String, errors: NonEmptyList[String]) {
    S.error(id + "Msg", "<-" + errors.list.reduce((acc, el) => acc + ", " + el))
  }

  private def clearErrorMessage(id: String) {
    S.error(id + "Msg", "")
  }


  private def clearFields() {
    name = ""
    country = ""
    postCode = ""
    city = ""
    street = ""
    password = ""
  }

  private def setFields(trialSite: TrialSite) {
    name = trialSite.name
    country = trialSite.country
    postCode = trialSite.postCode
    city = trialSite.city
    street = trialSite.street
    password = trialSite.password
  }

}

//
//object TrialSiteForm extends LiftScreen {
//
//  private val trialSiteService = DependencyFactory.trialSiteService
//  val name = field("Name", "")
//  val country = field("Country", "")
//  val postCode = field("PostCode", "")
//  val city = field("City", "")
//  val street = field("Street", "")
//  val password = field("Password", "")
//  val from = S.referer openOr "/"
//
//  def finish() {
//    TrialSite(name = name, country = country, postCode = postCode, city = city, street = street, password = password).either match {
//      case Left(x) => S.notice(x.toString())
//      case Right(site) =>  trialSiteService.create(site).either match {
//        case Left(x) => S.error(x)
//        case Right(b) => S.notice("saved " + b)
//      }
//    }
//  }
//
//}


