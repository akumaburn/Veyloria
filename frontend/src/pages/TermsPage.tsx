import React from 'react';
import { Link } from 'react-router-dom';
import { FileText } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { BackLink } from '@/components/layout/BackLink';

const TermsPage: React.FC = () => {
  return (
    <div className="bg-background min-h-dvh">
      <PageHeader
        sticky
        icon={FileText}
        title="Terms of Service"
        measure="max-w-2xl"
        lead={<BackLink to="/login" label="Back to sign in" />}
      />

      <main className="px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-2xl">
          <div className="rounded-2xl border border-border bg-card p-5 sm:p-8">
            <p className="text-sm text-muted-foreground mb-6">
              Last updated: {new Date().toLocaleDateString()}
            </p>

            <div className="space-y-6">
              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">1. Acceptance of Terms</h2>
                <p className="leading-relaxed text-foreground">
                  By accessing and using Veyloria, you accept and agree to be bound by the terms and conditions
                  of this agreement. If you do not agree to these terms, please do not use our service.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">2. Description of Service</h2>
                <p className="leading-relaxed text-foreground">
                  Veyloria provides an AI-run fantasy roleplay platform that allows users to interact with
                  various language models and AI characters. The service includes features such as
                  conversation management, character creation, and data export capabilities.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">3. User Accounts</h2>
                <p className="leading-relaxed text-foreground">
                  You are responsible for maintaining the confidentiality of your account credentials
                  and for all activities that occur under your account. You agree to notify us immediately
                  of any unauthorized use of your account.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">4. Acceptable Use</h2>
                <p className="leading-relaxed text-foreground mb-3">
                  You agree not to use the service for any unlawful purpose or in any way that could
                  damage, disable, or impair the service. Prohibited activities include but are not limited to:
                </p>
                <ul className="ml-4 list-disc list-inside space-y-1 text-foreground">
                  <li>Generating harmful, abusive, or illegal content</li>
                  <li>Attempting to bypass safety measures or content filters</li>
                  <li>Impersonating others or misrepresenting your identity</li>
                  <li>Interfering with the service's operation or security</li>
                </ul>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">5. Intellectual Property</h2>
                <p className="leading-relaxed text-foreground">
                  The service and its original content, features, and functionality are owned by Veyloria
                  and are protected by international copyright, trademark, and other intellectual property laws.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">6. User Content</h2>
                <p className="leading-relaxed text-foreground">
                  You retain ownership of content you create using the service. By using the service,
                  you grant us a license to process your content as necessary to provide the service.
                  We do not claim ownership of your conversations or custom characters.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">7. Limitation of Liability</h2>
                <p className="leading-relaxed text-foreground">
                  The service is provided "as is" without warranties of any kind. We shall not be liable
                  for any indirect, incidental, special, consequential, or punitive damages resulting
                  from your use of the service.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">8. Changes to Terms</h2>
                <p className="leading-relaxed text-foreground">
                  We reserve the right to modify these terms at any time. We will notify users of any
                  material changes by posting the new terms on the service. Your continued use of the
                  service after such modifications constitutes acceptance of the updated terms.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">9. Contact</h2>
                <p className="leading-relaxed text-foreground">
                  If you have any questions about these Terms of Service, please contact us at{' '}
                  <a href="mailto:support@veyloria.example.com" className="text-primary hover:underline">
                    support@veyloria.example.com
                  </a>
                </p>
              </section>
            </div>
          </div>

          {/* Footer link to Privacy */}
          <div className="mt-6 text-center">
            <p className="text-sm text-muted-foreground">
              See also our{' '}
              <Link to="/privacy" className="text-primary hover:underline">
                Privacy Policy
              </Link>
            </p>
          </div>
        </div>
      </main>
    </div>
  );
};

export default TermsPage;
