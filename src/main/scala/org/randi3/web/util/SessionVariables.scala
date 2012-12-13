package org.randi3.web.util

import net.liftweb.http.SessionVar
import org.randi3.model.{TrialSite, User, Trial}

object CurrentTrial extends SessionVar[Option[Trial]](None)

object CurrentUser extends SessionVar[Option[User]](None)

object CurrentSelectedUser extends SessionVar[Option[User]](None)

object CurrentSelectedTrialSite extends SessionVar[Option[TrialSite]](None)