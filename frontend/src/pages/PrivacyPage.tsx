import React from 'react';
import { Link } from 'react-router-dom';
import { Shield } from 'lucide-react';
import { PageHeader } from '@/components/layout/PageHeader';
import { BackLink } from '@/components/layout/BackLink';

const PrivacyPage: React.FC = () => {
  return (
    <div className="bg-background min-h-screen">
      <PageHeader
        sticky
        icon={Shield}
        title="Privacy Policy"
        measure="max-w-2xl"
        lead={<BackLink to="/login" label="Back to sign in" />}
      />

      <main className="px-4 py-8 sm:px-6">
        <div className="mx-auto max-w-2xl">
          <div className="rounded-2xl border border-border bg-card p-6 sm:p-8">
            <p className="text-sm text-muted-foreground mb-6">
              Last updated: {new Date().toLocaleDateString()}
            </p>

            <div className="space-y-6">
              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">1. Introduction</h2>
                <p className="leading-relaxed text-foreground">
                  Veyloria is committed to protecting your privacy. This Privacy Policy explains how we
                  collect, use, disclose, and safeguard your information when you use our AI conversation service.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">2. Information We Collect</h2>
                <p className="leading-relaxed text-foreground mb-3">
                  We collect information that you provide directly to us, including:
                </p>
                <ul className="ml-4 list-disc list-inside space-y-1 text-foreground">
                  <li>Account information (email address, username, password)</li>
                  <li>Profile information (display name, bio)</li>
                  <li>Conversation content and messages</li>
                  <li>Custom characters and their configurations</li>
                  <li>Usage preferences and settings</li>
                </ul>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">3. How We Use Your Information</h2>
                <p className="leading-relaxed text-foreground mb-3">
                  We use the collected information to:
                </p>
                <ul className="ml-4 list-disc list-inside space-y-1 text-foreground">
                  <li>Provide and maintain the service</li>
                  <li>Process and respond to your requests</li>
                  <li>Improve and personalize your experience</li>
                  <li>Send service-related communications</li>
                  <li>Ensure security and prevent abuse</li>
                </ul>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">4. Data Storage and Security</h2>
                <p className="leading-relaxed text-foreground">
                  Your data is stored securely using industry-standard encryption. We implement appropriate
                  technical and organizational measures to protect your personal information against
                  unauthorized access, alteration, disclosure, or destruction.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">5. Data Retention</h2>
                <p className="leading-relaxed text-foreground">
                  We retain your personal information for as long as your account is active or as needed
                  to provide services. You can delete your conversations and data at any time through
                  the Data Management section in your account settings.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">6. Information Sharing</h2>
                <p className="leading-relaxed text-foreground">
                  We do not sell, trade, or otherwise transfer your personal information to outside parties.
                  We may share information only in the following circumstances:
                </p>
                <ul className="ml-4 mt-3 list-disc list-inside space-y-1 text-foreground">
                  <li>With your explicit consent</li>
                  <li>To comply with legal obligations</li>
                  <li>To protect our rights and prevent fraud</li>
                  <li>With service providers who assist in our operations</li>
                </ul>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">7. Your Rights</h2>
                <p className="leading-relaxed text-foreground mb-3">
                  You have the right to:
                </p>
                <ul className="ml-4 list-disc list-inside space-y-1 text-foreground">
                  <li>Access your personal data</li>
                  <li>Correct inaccurate data</li>
                  <li>Request deletion of your data</li>
                  <li>Export your data in a portable format</li>
                  <li>Withdraw consent at any time</li>
                </ul>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">8. Cookies and Tracking</h2>
                <p className="leading-relaxed text-foreground">
                  We use essential cookies to maintain your session and preferences. We do not use
                  third-party tracking cookies or analytics services that track you across websites.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">9. Children's Privacy</h2>
                <p className="leading-relaxed text-foreground">
                  Our service is not intended for children under 13 years of age. We do not knowingly
                  collect personal information from children under 13.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">10. Changes to This Policy</h2>
                <p className="leading-relaxed text-foreground">
                  We may update this Privacy Policy from time to time. We will notify you of any changes
                  by posting the new policy on this page and updating the "Last updated" date.
                </p>
              </section>

              <section>
                <h2 className="text-xl font-semibold text-foreground mb-3">11. Contact Us</h2>
                <p className="leading-relaxed text-foreground">
                  If you have questions about this Privacy Policy or your personal data, please contact us at{' '}
                  <a href="mailto:privacy@veyloria.example.com" className="text-primary hover:underline">
                    privacy@veyloria.example.com
                  </a>
                </p>
              </section>
            </div>
          </div>

          {/* Footer link to Terms */}
          <div className="mt-6 text-center">
            <p className="text-sm text-muted-foreground">
              See also our{' '}
              <Link to="/terms" className="text-primary hover:underline">
                Terms of Service
              </Link>
            </p>
          </div>
        </div>
      </main>
    </div>
  );
};

export default PrivacyPage;
